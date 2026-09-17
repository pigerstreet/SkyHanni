#!/usr/bin/env bash
# fork-guard scan — reviews the commits that just landed on a guarded branch.
#
# Runs in GitHub Actions. Configuration comes from the environment:
#   TARGET_BRANCH        branch being guarded (required)
#   GUARD_SCRIPTS_DIR    absolute dir holding prompt.md (required)
#   EVENT_BASE_SHA       github.event.before (push events)
#   EVENT_HEAD_SHA       github.sha (push events)
#   DISPATCH_BASE_SHA    manual base override
#   DISPATCH_HEAD_SHA    manual head override
#   ANTHROPIC_*          model/gateway config for the claude CLI
#   CHUNK_LINES, MAX_CHUNKS, PARALLEL, PER_CHUNK_TIMEOUT, MAX_FILE_LINES  tuning knobs
#
# Writes:
#   $GITHUB_OUTPUT                     verdict=..., head_sha=..., base_sha=...
#   $GITHUB_WORKSPACE/.fork-guard/verdict.json   machine-readable result
#   $GITHUB_STEP_SUMMARY               human-readable report
#
# Exits 0 whenever it produced a verdict (the workflow acts on the verdict, not the
# exit code). Non-zero only when it could not start at all.
set -uo pipefail

TARGET_BRANCH="${TARGET_BRANCH:?TARGET_BRANCH must be set}"
GUARD_SCRIPTS_DIR="${GUARD_SCRIPTS_DIR:?GUARD_SCRIPTS_DIR must be set}"

WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"
# Keep results out of the repo tree so nothing can be committed by accident.
OUT_DIR="${FORK_GUARD_OUT:-${RUNNER_TEMP:-/tmp}/fork-guard}"
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}/verdicts"

WORK="$(mktemp -d)"
VERDICTS_DIR="${OUT_DIR}/verdicts"
trap 'rm -rf "${WORK}"' EXIT

CHUNK_LINES="${CHUNK_LINES:-1200}"
MAX_CHUNKS="${MAX_CHUNKS:-8}"
PARALLEL="${PARALLEL:-4}"
PER_CHUNK_TIMEOUT="${PER_CHUNK_TIMEOUT:-240}"
MAX_FILE_LINES="${MAX_FILE_LINES:-500}"
SCAN_MODEL="${ANTHROPIC_MODEL:-orcarouter/auto}"

log() { printf '[fork-guard] %s\n' "$*"; }

summary() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n' "$*" >>"${GITHUB_STEP_SUMMARY}"; fi
}

set_output() {
  if [ -n "${GITHUB_OUTPUT:-}" ]; then printf '%s=%s\n' "$1" "$2" >>"${GITHUB_OUTPUT}"; fi
}

# finish <verdict> <json-file> — emit result and exit
finish() {
  local verdict="$1" json="$2"
  cp "${json}" "${OUT_DIR}/verdict.json"
  set_output verdict "${verdict}"
  set_output head_sha "${HEAD_SHA:-}"
  set_output base_sha "${BASE_SHA:-}"
  log "verdict: ${verdict}"
  exit 0
}

die() {
  log "FATAL: $*"
  summary "## fork-guard could not run"
  summary ""
  summary "\`\`\`"
  summary "$*"
  summary "\`\`\`"
  summary ""
  summary "No commits were scanned. Re-run this workflow once the cause is fixed."
  local f="${WORK}/fatal.json"
  printf '{"verdict":"ERROR","findings":[],"error":"%s"}\n' "$(printf '%s' "$*" | tr -d '"\\' | tr '\n' ' ')" >"${f}"
  finish ERROR "${f}"
}

command -v git >/dev/null || die "git not found"
command -v python3 >/dev/null || die "python3 not found"

# --------------------------------------------------------------------- range

HEAD_SHA="${DISPATCH_HEAD_SHA:-}"
BASE_SHA="${DISPATCH_BASE_SHA:-}"
if [ -z "${HEAD_SHA}" ] && [ "${GITHUB_EVENT_NAME:-}" = "push" ]; then
  HEAD_SHA="${EVENT_HEAD_SHA:-}"
fi
if [ -z "${HEAD_SHA}" ]; then
  HEAD_SHA="$(git rev-parse --verify --quiet "origin/${TARGET_BRANCH}" || true)"
fi
[ -n "${HEAD_SHA}" ] || die "could not determine the head commit for ${TARGET_BRANCH}"

if ! git cat-file -e "${HEAD_SHA}^{commit}" 2>/dev/null; then
  git fetch --quiet --no-tags origin "${TARGET_BRANCH}" || die "cannot fetch ${TARGET_BRANCH}"
fi
HEAD_SHA="$(git rev-parse --verify "${HEAD_SHA}^{commit}")" || die "bad head sha"

# Bookmark left by the previous clean scan, else a 7-day lookback.
fallback_base() {
  local tag="guard-last-scan-${TARGET_BRANCH}"
  local t
  if t="$(git rev-parse --verify --quiet "refs/tags/${tag}^{commit}")" && [ -n "${t}" ]; then
    if git merge-base --is-ancestor "${t}" "${HEAD_SHA}" 2>/dev/null; then
      printf '%s' "${t}"
      return
    fi
  fi
  local old
  old="$(git rev-list -1 --before='7 days ago' "${HEAD_SHA}" 2>/dev/null || true)"
  if [ -n "${old}" ]; then printf '%s' "${old}"; return; fi
  git rev-parse --verify --quiet "${HEAD_SHA}^" || printf '%s' "${HEAD_SHA}"
}

if [ -z "${BASE_SHA}" ] && [ "${GITHUB_EVENT_NAME:-}" = "push" ]; then
  BASE_SHA="${EVENT_BASE_SHA:-}"
fi

usable_base=1
case "${BASE_SHA}" in
  '' | *[!0-9a-fA-F]*) usable_base=0 ;;
  0000000000000000000000000000000000000000) usable_base=0 ;;
esac
if [ "${usable_base}" = "1" ]; then
  git cat-file -e "${BASE_SHA}^{commit}" 2>/dev/null || usable_base=0
fi
if [ "${usable_base}" = "1" ]; then
  git merge-base --is-ancestor "${BASE_SHA}" "${HEAD_SHA}" 2>/dev/null || usable_base=0
fi
if [ "${usable_base}" = "0" ]; then
  log "push base unusable (new branch or force-push) — falling back to the last scanned bookmark"
  BASE_SHA="$(fallback_base)"
fi
BASE_SHA="$(git rev-parse --verify "${BASE_SHA}^{commit}")" || die "bad base sha"

set_output head_sha "${HEAD_SHA}"
set_output base_sha "${BASE_SHA}"
log "range: ${BASE_SHA:0:9}..${HEAD_SHA:0:9} on ${TARGET_BRANCH}"

if [ "${BASE_SHA}" = "${HEAD_SHA}" ]; then
  summary "## fork-guard — nothing new to scan"
  summary ""
  summary "Branch \`${TARGET_BRANCH}\` is already at the last scanned commit \`${HEAD_SHA:0:9}\`."
  printf '{"verdict":"CLEAN","findings":[],"scanned_files":0,"note":"nothing new"}\n' >"${WORK}/empty.json"
  finish CLEAN "${WORK}/empty.json"
fi

git checkout --quiet --detach "${HEAD_SHA}" || die "could not check out ${HEAD_SHA:0:9}"

# -------------------------------------------------------------------- triage

git diff --name-status --no-renames "${BASE_SHA}" "${HEAD_SHA}" >"${WORK}/namestatus.txt" ||
  die "git diff --name-status failed"

classify() {
  case "$1" in
    *.md | *.markdown | *.txt | *.rst | *.adoc) echo skip_doc ;;
    *.png | *.jpg | *.jpeg | *.gif | *.webp | *.ico | *.bmp | *.xcf) echo skip_image ;;
    *.ogg | *.mp3 | *.wav | *.flac) echo skip_audio ;;
    */lang/*.json | *.lang | */lang/*.lang) echo skip_lang ;;
    .github/* | */.github/*) echo t0_ci ;;
    gradlew | gradlew.bat | gradle/wrapper/* | */gradle/wrapper/*) echo t0_ci ;;
    build.gradle | build.gradle.kts | settings.gradle | settings.gradle.kts) echo t0_ci ;;
    */build.gradle | */build.gradle.kts | */settings.gradle | */settings.gradle.kts) echo t0_ci ;;
    buildSrc/* | */buildSrc/* | gradle.properties | */gradle.properties) echo t0_ci ;;
    *.gradle | *.gradle.kts) echo t0_ci ;;
    *.sh | *.bash | *.bat | *.cmd | *.ps1 | *.psm1) echo t0_ci ;;
    Dockerfile* | */Dockerfile* | .gitmodules | */.gitmodules) echo t0_ci ;;
    *.jar | *.class | *.dll | *.so | *.dylib | *.exe | *.bin | *.node | *.zip | *.gz) echo t1_binary ;;
    *.kt | *.java | *.groovy | *.kts) echo t2_code ;;
    *.py | *.js | *.ts | *.jsx | *.tsx | *.mjs | *.cjs) echo t2_code ;;
    *.yml | *.yaml | *.json | *.toml | *.xml | *.properties | *.conf | *.cfg | *.ini) echo t2_config ;;
    *) echo t3_other ;;
  esac
}

declare -a T0=() T1=() T2=() T3=() SKIPPED=()
while IFS=$'\t' read -r status path rest; do
  [ -n "${path:-}" ] || continue
  case "${status}" in
    R* | C*) path="${rest:-${path}}" ;; # renamed/copied: use the destination
  esac
  kind="$(classify "${path}")"
  case "${kind}" in
    skip_*) SKIPPED+=("${status:0:1} ${path}") ;;
    t0_ci) T0+=("${status:0:1} ${path}") ;;
    t1_binary) T1+=("${status:0:1} ${path}") ;;
    t2_code | t2_config) T2+=("${status:0:1} ${path}") ;;
    *) T3+=("${status:0:1} ${path}") ;;
  esac
done <"${WORK}/namestatus.txt"

log "triage: t0=${#T0[@]} t1=${#T1[@]} t2=${#T2[@]} t3=${#T3[@]} skipped=${#SKIPPED[@]}"

# Nothing executable, no build/CI change, no binaries: no model call at all.
if [ "$((${#T0[@]} + ${#T1[@]} + ${#T2[@]}))" -eq 0 ]; then
  summary "## fork-guard — no scannable changes"
  summary ""
  summary "Range \`${BASE_SHA:0:9}..${HEAD_SHA:0:9}\` on \`${TARGET_BRANCH}\` touched ${#T3[@]} low-risk file(s)"
  summary "and ${#SKIPPED[@]} documentation/asset file(s). No code, build, CI or binary change — skipped the model scan."
  printf '{"verdict":"CLEAN","findings":[],"note":"no scannable files","skipped_count":%d}\n' "${#SKIPPED[@]}" >"${WORK}/clean.json"
  finish CLEAN "${WORK}/clean.json"
fi

# ------------------------------------------------------------------- chunking

mkdir -p "${WORK}/chunks"
CHUNK_INDEX=0
CUR_LINES=0
CUR_FILES=0
CUR="${WORK}/chunks/chunk-000.diff"
CUR_LIST="${WORK}/chunks/chunk-000.files"
: >"${CUR}"
: >"${CUR_LIST}"
UNSCANNED=()
TRUNCATED=()
CHUNKED_FILES=0

new_chunk() {
  if [ "${CUR_FILES}" -eq 0 ]; then return; fi
  CHUNK_INDEX=$((CHUNK_INDEX + 1))
  if [ "${CHUNK_INDEX}" -ge "${MAX_CHUNKS}" ]; then return; fi
  CUR="$(printf '%s/chunks/chunk-%03d.diff' "${WORK}" "${CHUNK_INDEX}")"
  CUR_LIST="${CUR%.diff}.files"
  : >"${CUR}"
  : >"${CUR_LIST}"
  CUR_LINES=0
  CUR_FILES=0
}

add_file() {
  local status="$1" path="$2"
  if [ "${CHUNK_INDEX}" -ge "${MAX_CHUNKS}" ]; then
    UNSCANNED+=("${status} ${path}")
    return
  fi
  {
    printf '\n=== %s %s ===\n' "${status}" "${path}"
    if [ "${status}" = "D" ]; then
      printf '(file deleted in this range; previous contents are not re-reviewed)\n'
    else
      git log -1 --format='last touched by: %h %an <%ae> %ad — %s' --date=short \
        "${BASE_SHA}..${HEAD_SHA}" -- "${path}" 2>/dev/null || true
    fi
  } >>"${CUR}"

  local body lines
  if [ "${status}" = "D" ]; then
    body="(deleted)"
    lines=1
  else
    body="$(git diff --no-color --no-renames "${BASE_SHA}" "${HEAD_SHA}" -- "${path}" 2>/dev/null || true)"
    lines="$(printf '%s\n' "${body}" | wc -l)"
    if [ "${lines}" -gt "${MAX_FILE_LINES}" ]; then
      body="$(printf '%s\n' "${body}" | head -n "${MAX_FILE_LINES}")"
      body="${body}"$'\n'"[... ${lines} diff lines total, ${MAX_FILE_LINES} shown — read the file in the repo for the rest ...]"
      lines="${MAX_FILE_LINES}"
      TRUNCATED+=("${path} (${lines} of a longer diff)")
    fi
  fi
  printf '%s\n' "${body}" >>"${CUR}"
  printf '%s %s\n' "${status}" "${path}" >>"${CUR_LIST}"
  CUR_LINES=$((CUR_LINES + lines + 4))
  CUR_FILES=$((CUR_FILES + 1))
  CHUNKED_FILES=$((CHUNKED_FILES + 1))
  if [ "${CUR_LINES}" -ge "${CHUNK_LINES}" ]; then new_chunk; fi
}

for bucket in T0 T1 T2 T3; do
  declare -n arr="${bucket}"
  for entry in "${arr[@]}"; do
    add_file "${entry:0:1}" "${entry:2}"
  done
  unset -n arr
done

TOTAL_CHUNKS=$((CHUNK_INDEX + 1))
if [ "${CUR_FILES}" -eq 0 ] && [ "${CHUNK_INDEX}" -gt 0 ]; then
  rm -f "${CUR}" "${CUR_LIST}"
  TOTAL_CHUNKS="${CHUNK_INDEX}"
fi
log "built ${TOTAL_CHUNKS} chunk(s) covering ${CHUNKED_FILES} file(s); ${#UNSCANNED[@]} left unscanned"

if [ "${TOTAL_CHUNKS}" -eq 0 ]; then
  summary "## fork-guard — nothing to review"
  printf '{"verdict":"CLEAN","findings":[],"note":"no chunkable diff"}\n' >"${WORK}/nochunk.json"
  finish CLEAN "${WORK}/nochunk.json"
fi

# --------------------------------------------------------------- agent calls

cat >"${WORK}/run_chunk.sh" <<'RUNNER'
#!/usr/bin/env bash
set -uo pipefail
chunk="$1"
name="$(basename "${chunk}" .diff)"
files="$(tr '\n' ' ' < "${chunk%.diff}.files" 2>/dev/null || true)"
prompt="Review this diff chunk for malicious code.

Diff chunk file: ${chunk}
Files it covers: ${files}
Commit range: ${BASE_SHA}..${HEAD_SHA} on branch ${TARGET_BRANCH}
The repository is checked out at ${HEAD_SHA} — use Read/Grep for surrounding context when the diff alone is not enough to judge.

Read the chunk file first, then answer with only the JSON object your instructions describe."
timeout "${PER_CHUNK_TIMEOUT}" claude -p "${prompt}" \
  --append-system-prompt "$(cat "${GUARD_SCRIPTS_DIR}/prompt.md")" \
  --output-format json \
  --allowedTools Read Grep Glob \
  --max-turns 8 \
  --model "${SCAN_MODEL}" \
  >"${VERDICTS_DIR}/${name}.raw" 2>"${VERDICTS_DIR}/${name}.err"
printf '%s' "$?" >"${VERDICTS_DIR}/${name}.rc"
RUNNER
chmod +x "${WORK}/run_chunk.sh"

export BASE_SHA HEAD_SHA TARGET_BRANCH GUARD_SCRIPTS_DIR VERDICTS_DIR PER_CHUNK_TIMEOUT SCAN_MODEL

run_all() {
  ls "${WORK}"/chunks/chunk-*.diff 2>/dev/null |
    xargs -r -n1 -P "${PARALLEL}" bash "${WORK}/run_chunk.sh"
}

START="$(date +%s)"
run_all

# Retry once, serially, anything that produced no parsable verdict.
RETRIED=()
for raw in "${VERDICTS_DIR}"/chunk-*.raw; do
  [ -e "${raw}" ] || continue
  name="$(basename "${raw}" .raw)"
  if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "${raw}" 2>/dev/null; then
    RETRIED+=("${name}")
  fi
done
if [ "${#RETRIED[@]}" -gt 0 ]; then
  log "retrying ${#RETRIED[@]} chunk(s): ${RETRIED[*]}"
  for name in "${RETRIED[@]}"; do
    PER_CHUNK_TIMEOUT=$((PER_CHUNK_TIMEOUT * 2)) bash "${WORK}/run_chunk.sh" "${WORK}/chunks/${name}.diff" || true
  done
fi
ELAPSED=$(( $(date +%s) - START ))

# ------------------------------------------------------------- aggregation

cat >"${WORK}/aggregate.py" <<'PY'
import glob, json, os, sys

verdicts_dir, chunks_dir, out_path, meta_path = sys.argv[1:5]
rank = {"CLEAN": 0, "SUSPICIOUS": 1, "MALICIOUS": 2}

chunks = sorted(os.path.basename(p)[: -len(".diff")] for p in glob.glob(os.path.join(chunks_dir, "chunk-*.diff")))
findings, errors = [], []

def extract(text):
    text = text.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return None
    try:
        return json.loads(text[start : end + 1])
    except Exception:
        return None

for name in chunks:
    raw = os.path.join(verdicts_dir, name + ".raw")
    covered = []
    flist = os.path.join(chunks_dir, name + ".files")
    if os.path.exists(flist):
        covered = [l.strip() for l in open(flist, encoding="utf-8", errors="replace") if l.strip()]
    payload = None
    if os.path.exists(raw) and os.path.getsize(raw) > 0:
        try:
            with open(raw, encoding="utf-8", errors="replace") as fh:
                envelope = json.load(fh)
            payload = extract(envelope.get("result") or "")
        except Exception:
            payload = None
    if payload is None:
        rc = ""
        rcp = os.path.join(verdicts_dir, name + ".rc")
        if os.path.exists(rcp):
            rc = open(rcp, encoding="utf-8", errors="replace").read().strip()
        err = ""
        errp = os.path.join(verdicts_dir, name + ".err")
        if os.path.exists(errp):
            err = open(errp, encoding="utf-8", errors="replace").read().strip()[-400:]
        errors.append({"chunk": name, "exit_code": rc, "stderr_tail": err, "files": covered})
        continue
    for f in payload.get("findings") or []:
        if isinstance(f, dict):
            f.setdefault("chunk", name)
            findings.append(f)

worst = "CLEAN"
for f in findings:
    sev = str(f.get("severity") or f.get("verdict") or "").upper()
    if sev in rank and rank[sev] > rank[worst]:
        worst = sev
if errors:
    # Content that was never reviewed cannot be called clean.
    worst = "MALICIOUS" if worst == "MALICIOUS" else "SUSPICIOUS"

result = {"verdict": worst, "findings": findings, "unscanned_chunks": errors}
json.dump(result, open(out_path, "w", encoding="utf-8"), indent=2)

meta = json.load(open(meta_path, encoding="utf-8"))
meta.update({"chunks_total": len(chunks), "chunks_failed": len(errors),
             "malicious": sum(1 for f in findings if str(f.get("severity", "")).upper() == "MALICIOUS"),
             "suspicious": sum(1 for f in findings if str(f.get("severity", "")).upper() == "SUSPICIOUS")})
json.dump(meta, open(meta_path, "w", encoding="utf-8"), indent=2)
print(worst)
PY

META="${WORK}/meta.json"
printf '{"model":"%s","elapsed_seconds":%d,"files_chunked":%d}\n' "${SCAN_MODEL}" "${ELAPSED}" "${CHUNKED_FILES}" >"${META}"

AGG="${WORK}/aggregate.json"
VERDICT="$(python3 "${WORK}/aggregate.py" "${VERDICTS_DIR}" "${WORK}/chunks" "${AGG}" "${META}" 2>"${WORK}/agg.err")" ||
  VERDICT=""
if [ -z "${VERDICT}" ]; then
  cat "${WORK}/agg.err" >&2 || true
  die "verdict aggregation failed"
fi

# merge triage bookkeeping into the verdict for the reporting step
printf '%s\n' "${SKIPPED[@]+"${SKIPPED[@]}"}" >"${WORK}/skipped.txt"
printf '%s\n' "${UNSCANNED[@]+"${UNSCANNED[@]}"}" >"${WORK}/unscanned.txt"
printf '%s\n' "${TRUNCATED[@]+"${TRUNCATED[@]}"}" >"${WORK}/truncated.txt"
python3 - "$AGG" "$META" "$BASE_SHA" "$HEAD_SHA" "$TARGET_BRANCH" \
  "$WORK/skipped.txt" "$WORK/unscanned.txt" "$WORK/truncated.txt" <<'PY' || die "verdict merge failed"
import json, sys
agg, meta_path, base, head, branch, *lists = sys.argv[1:]
data = json.load(open(agg, encoding="utf-8"))
data["meta"] = json.load(open(meta_path, encoding="utf-8"))
data["meta"].update({"base_sha": base, "head_sha": head, "branch": branch})
for key, path in zip(("skipped_files", "unscanned_files", "truncated_files"), lists):
    data[key] = [x for x in open(path, encoding="utf-8").read().splitlines() if x.strip()]
json.dump(data, open(agg, "w", encoding="utf-8"), indent=2)
PY

# --------------------------------------------------------------- reporting

{
  printf '## fork-guard — %s\n\n' "${VERDICT}"
  printf 'Range `%s..%s` on `%s` · %d file(s) reviewed in %d chunk(s) · %s · %ds\n\n' \
    "${BASE_SHA:0:9}" "${HEAD_SHA:0:9}" "${TARGET_BRANCH}" "${CHUNKED_FILES}" "${TOTAL_CHUNKS}" "${SCAN_MODEL}" "${ELAPSED}"
} | summary

if [ "${#SKIPPED[@]}" -gt 0 ]; then
  { printf '<details><summary>%d documentation/asset file(s) not sent to the model</summary>\n\n```\n' "${#SKIPPED[@]}"
    printf '%s\n' "${SKIPPED[@]}" | head -n 60
    printf '```\n\n</details>\n' ; } | summary
fi
if [ "${#TRUNCATED[@]}" -gt 0 ]; then
  { printf '<details><summary>%d diff(s) truncated at %s lines</summary>\n\n```\n' "${#TRUNCATED[@]}" "${MAX_FILE_LINES}"
    printf '%s\n' "${TRUNCATED[@]}"
    printf '```\n\n</details>\n'; } | summary
fi
if [ "${#UNSCANNED[@]}" -gt 0 ]; then
  { printf '### Not scanned (chunk cap %s reached)\n\n```\n' "${MAX_CHUNKS}"
    printf '%s\n' "${UNSCANNED[@]}" | head -n 100
    printf '```\n\nRe-run this workflow with `workflow_dispatch` on a narrower range to cover them.\n'; } | summary
fi

python3 - "${AGG}" >>"${GITHUB_STEP_SUMMARY:-/dev/null}" <<'PY' || true
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
findings = data.get("findings") or []
if findings:
    print("\n### Findings\n")
    print("| Severity | File | Lines | Category | Reason |")
    print("|---|---|---|---|---|")
    for f in findings:
        cells = [str(f.get("severity", "?")), str(f.get("file", "?")), str(f.get("lines", "?")),
                 str(f.get("category", "?")), str(f.get("reason", "")).replace("|", "\\|").replace("\n", " ")]
        print("| " + " | ".join(c[:300] for c in cells) + " |")
for c in data.get("unscanned_chunks") or []:
    print(f"\n> chunk `{c.get('chunk')}` could not be reviewed (exit {c.get('exit_code')}): "
          f"{(c.get('stderr_tail') or 'no output')[:200]}")
PY

finish "${VERDICT}" "${AGG}"
