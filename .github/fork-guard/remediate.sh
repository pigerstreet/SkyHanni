#!/usr/bin/env bash
# fork-guard remediation — acts on the verdict produced by scan.sh.
#
#   MALICIOUS  -> revert the offending commits, push, cancel in-flight runs, open an issue
#   SUSPICIOUS -> open an issue only (never touch the branch on unproven findings)
#   ERROR      -> fail loudly, no issue (infrastructure problem, not a finding)
#
# Reads  $GITHUB_WORKSPACE/.fork-guard/verdict.json
# Env:   TARGET_BRANCH, GH_TOKEN, GITHUB_REPOSITORY, GITHUB_RUN_ID, GITHUB_SERVER_URL
# Exits  0 only when the verdict was CLEAN (or there was nothing to do).
set -uo pipefail

TARGET_BRANCH="${TARGET_BRANCH:?}"
WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"
OUT_DIR="${FORK_GUARD_OUT:-${RUNNER_TEMP:-/tmp}/fork-guard}"
VERDICT_FILE="${OUT_DIR}/verdict.json"

log() { printf '[fork-guard] %s\n' "$*"; }
summary() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n' "$*" >>"${GITHUB_STEP_SUMMARY}"; fi
}

if [ ! -f "${VERDICT_FILE}" ]; then
  summary "### fork-guard produced no verdict"
  summary "The scan step did not finish. No changes were made and no commits were reviewed — re-run the workflow."
  exit 1
fi

VERDICT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("verdict","ERROR"))' "${VERDICT_FILE}")"
log "acting on verdict: ${VERDICT}"

case "${VERDICT}" in
  CLEAN)
    exit 0
    ;;
  ERROR)
    summary "### fork-guard errored"
    summary "The scan could not complete (see log above). No changes were made; **the pushed commits were not reviewed.** Re-run the workflow."
    exit 1
    ;;
esac

export GIT_AUTHOR_NAME="fork-guard[bot]"
export GIT_AUTHOR_EMAIL="fork-guard@users.noreply.github.com"
export GIT_COMMITTER_NAME="${GIT_AUTHOR_NAME}"
export GIT_COMMITTER_EMAIL="${GIT_AUTHOR_EMAIL}"

DRY_RUN="${DRY_RUN:-false}"
if [ "${DRY_RUN}" = "true" ] || [ "${DRY_RUN}" = "1" ]; then
  log "DRY RUN — reporting only, the branch will not be touched"
  summary "### fork-guard DRY RUN"
  summary ""
  summary "Findings below were **not** acted on: nothing was reverted, pushed, cancelled or filed."
  python3 - "${VERDICT_FILE}" >>"${GITHUB_STEP_SUMMARY:-/dev/null}" <<'PY' || true
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
print(f"\nVerdict: **{d.get('verdict')}** — {len(d.get('findings') or [])} finding(s)\n")
for f in d.get("findings") or []:
    print(f"- `{f.get('severity')}` {f.get('file')}:{f.get('lines')} — {f.get('reason','')[:300]}")
PY
  exit 1
fi

RANGE_COMMITS="${OUT_DIR}/range_commits.txt"
BASE_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("meta",{}).get("base_sha",""))' "${VERDICT_FILE}")"
HEAD_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("meta",{}).get("head_sha",""))' "${VERDICT_FILE}")"
if [ -z "${BASE_SHA}" ] || [ -z "${HEAD_SHA}" ]; then
  summary "### fork-guard findings without scan metadata"
  summary "The verdict file is missing base/head SHAs, so commits cannot be attributed safely. **Nothing was reverted.** Findings are in the run log — review them manually."
  log "verdict file has no meta.base_sha/head_sha; refusing to revert"
  exit 1
fi
git rev-list "${BASE_SHA}..${HEAD_SHA}" >"${RANGE_COMMITS}"

REVERTED=()
REVERT_COMMITS=()
REVERT_FAILED=()
MANUAL=()

# ------------------------------------------------------------- blame -> commit
resolve_commits() {
  # echoes commit shas (newest first) that introduced the flagged lines
  local file="$1" lines="$2" start end found
  start="${lines%%-*}"
  end="${lines##*-}"
  [[ "${start}" =~ ^[0-9]+$ ]] || start=""
  [[ "${end}" =~ ^[0-9]+$ ]] || end="${start}"
  found=""
  if [ -n "${start}" ] && [ -f "${file}" ]; then
    found="$(git blame -L "${start},${end}" --porcelain -- "${file}" 2>/dev/null |
      grep -oE '^[0-9a-f]{40}' | sort -u)"
  fi
  if [ -z "${found}" ]; then
    # line numbers unusable — fall back to every range commit that touched the file
    found="$(git log --format=%H "${BASE_SHA}..${HEAD_SHA}" -- "${file}" 2>/dev/null)"
  fi
  # keep only commits inside the scanned range, newest first
  local c idx
  for c in ${found}; do
    idx="$(grep -n "^${c}$" "${RANGE_COMMITS}" | head -n1 | cut -d: -f1)"
    [ -n "${idx}" ] && printf '%06d %s\n' "${idx}" "${c}"
  done
}

if [ "${VERDICT}" = "MALICIOUS" ]; then
  # collect the commit set from all malicious findings, newest first, deduplicated
  mapfile -t TARGETS < <(
    python3 -c '
import json, sys
data = json.load(open(sys.argv[1]))
for f in data.get("findings") or []:
    if str(f.get("severity", "")).upper() == "MALICIOUS":
        print(f.get("file", "") + "\t" + str(f.get("lines", "")))
' "${VERDICT_FILE}" | while IFS=$'\t' read -r f l; do
      [ -n "${f}" ] || continue
      resolve_commits "${f}" "${l}"
    done | sort -n -u | awk '{print $2}'
  )

  if [ "${#TARGETS[@]}" -eq 0 ]; then
    MANUAL+=("no in-range commit could be attributed to the flagged lines — remove the code manually")
  fi

  for c in "${TARGETS[@]+"${TARGETS[@]}"}"; do
    if [ "$(git rev-list --parents -n1 "${c}" | wc -w)" -gt 2 ]; then
      MANUAL+=("${c:0:9} is a merge commit — reverting it would undo the whole sync, so it was left alone")
      continue
    fi
    subj="$(git log -1 --format=%s "${c}")"
    if git revert --no-commit "${c}" >/dev/null 2>&1 &&
      git commit --quiet -m "Revert \"${subj}\" (fork-guard: malicious code)

fork-guard flagged code introduced by ${c} as MALICIOUS and removed it
automatically. See the fork-guard issue on this repository for the evidence
and for how to restore these changes (revert this commit).

This reverts commit ${c}.

[skip ci]"; then
      REVERTED+=("${c}")
      REVERT_COMMITS+=("$(git rev-parse HEAD)")
      log "reverted ${c:0:9} (${subj})"
    else
      git revert --abort >/dev/null 2>&1 || git reset --hard --quiet HEAD
      REVERT_FAILED+=("${c}")
      log "could not revert ${c:0:9} cleanly"
    fi
  done

  if [ "${#REVERTED[@]}" -gt 0 ]; then
    if git push origin "HEAD:refs/heads/${TARGET_BRANCH}" 2>"${OUT_DIR}/push.err"; then
      log "pushed ${#REVERTED[@]} revert commit(s) to ${TARGET_BRANCH}"
    else
      log "push rejected, retrying on top of the current branch tip"
      git fetch --quiet origin "${TARGET_BRANCH}"
      if git rebase "origin/${TARGET_BRANCH}" >/dev/null 2>&1 &&
        git push origin "HEAD:refs/heads/${TARGET_BRANCH}" 2>>"${OUT_DIR}/push.err"; then
        log "pushed after rebase"
      else
        git rebase --abort >/dev/null 2>&1 || true
        REVERT_FAILED+=("${REVERTED[@]+"${REVERTED[@]}"}")
        REVERTED=()
        REVERT_COMMITS=()
        MANUAL+=("push to ${TARGET_BRANCH} was rejected — apply the reverts manually ($(tail -n 2 "${OUT_DIR}/push.err" | tr '\n' ' '))")
      fi
    fi
  fi

  # stop any build still running on this branch (never our own run)
  if command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}" ]; then
    for st in in_progress queued; do
      gh run list --branch "${TARGET_BRANCH}" --status "${st}" --limit 30 \
        --json databaseId --jq '.[].databaseId' 2>/dev/null | while read -r rid; do
        [ -n "${rid}" ] && [ "${rid}" != "${GITHUB_RUN_ID:-}" ] && gh run cancel "${rid}" >/dev/null 2>&1 || true
      done
    done
    log "cancelled in-flight runs on ${TARGET_BRANCH} (if any)"
  fi
fi

# ------------------------------------------------------------------- the issue

RUN_URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID:-0}"
ISSUE_BODY="${OUT_DIR}/issue.md"

python3 - "${VERDICT_FILE}" "${ISSUE_BODY}" "${RUN_URL}" "${TARGET_BRANCH}" \
  "$(printf '%s\n' "${REVERTED[@]+"${REVERTED[@]}"}")" \
  "$(printf '%s\n' "${REVERT_COMMITS[@]+"${REVERT_COMMITS[@]}"}")" \
  "$(printf '%s\n' "${REVERT_FAILED[@]+"${REVERT_FAILED[@]}"}")" \
  "$(printf '%s\n' "${MANUAL[@]+"${MANUAL[@]}"}")" <<'PY'
import json, subprocess, sys

verdict_file, out, run_url, branch = sys.argv[1:5]
reverted = [x for x in sys.argv[5].splitlines() if x.strip()]
revert_commits = [x for x in sys.argv[6].splitlines() if x.strip()]
failed = [x for x in sys.argv[7].splitlines() if x.strip()]
manual = [x for x in sys.argv[8].splitlines() if x.strip()]
data = json.load(open(verdict_file, encoding="utf-8"))
findings = data.get("findings") or []
meta = data.get("meta", {})

def short(sha):
    try:
        return subprocess.check_output(["git", "log", "-1", "--format=%h %s", sha.strip()],
                                       text=True).strip()
    except Exception:
        return sha[:9]

v = data.get("verdict", "?")
L = []
if v == "MALICIOUS":
    L.append(f"fork-guard reviewed `{meta.get('base_sha','?')[:9]}..{meta.get('head_sha','?')[:9]}` "
             f"on `{branch}` and found code it assessed as **malicious**.")
else:
    L.append(f"fork-guard reviewed `{meta.get('base_sha','?')[:9]}..{meta.get('head_sha','?')[:9]}` "
             f"on `{branch}` and found code that needs **your review**. "
             f"Nothing was changed on the branch automatically — findings were not conclusive enough to revert.")

L.append("\n## Findings\n")
for f in findings:
    sev = str(f.get("severity", "?")).upper()
    icon = "🚨" if sev == "MALICIOUS" else "⚠️"
    L.append(f"### {icon} {sev} — `{f.get('file','?')}` (lines {f.get('lines','?')})")
    L.append(f"*Category:* {f.get('category','?')}\n")
    L.append(f"{f.get('reason','')}\n")
    ev = str(f.get("evidence", "")).strip()
    if ev:
        L.append("```")
        L.append(ev[:2000])
        L.append("```\n")

if reverted:
    L.append("\n## Removed automatically\n")
    L.append(f"These commits were reverted and the reverts pushed to `{branch}`:\n")
    for r in reverted:
        L.append(f"- `{short(r)}`")
    L.append("\n**To restore any of them** (if a finding turns out to be wrong):\n")
    L.append("```bash")
    L.append("git fetch origin")
    L.append(f"git checkout {branch} && git pull")
    if revert_commits:
        L.append("git revert " + " ".join(c[:12] for c in revert_commits) + "   # undoes the automatic removal")
    L.append("git push origin HEAD")
    L.append("```")
if failed:
    L.append("\n## Revert failed — manual action required\n")
    for r in failed:
        L.append(f"- `{short(r)}` could not be reverted cleanly (later commits touch the same lines).")
if manual:
    L.append("\n## Notes\n")
    for m in manual:
        L.append(f"- {m}")

for c in data.get("unscanned_chunks") or []:
    L.append(f"\n> ⚠️ chunk `{c.get('chunk')}` could not be reviewed by the model "
             f"(exit {c.get('exit_code')}) — those files were NOT scanned.")
un = data.get("unscanned_files") or []
if un:
    L.append("\n<details><summary>Files not scanned (chunk cap reached)</summary>\n")
    L.append("```")
    L.extend(un[:100])
    L.append("```")
    L.append("\n</details>")

L.append("\n---")
L.append(f"*Scan report: {run_url}*")
L.append("\n*If the flagged commits came from upstream, consider reporting this to the upstream "
         "project — their other users are exposed too. Your reverts stay on your branch, but "
         "avoid resetting or re-syncing over them.*")

open(out, "w", encoding="utf-8").write("\n".join(L) + "\n")
PY

TITLE_PREFIX="🚨 fork-guard: malicious code"
[ "${VERDICT}" = "MALICIOUS" ] || TITLE_PREFIX="⚠️ fork-guard: suspicious code"
ISSUE_TITLE="${TITLE_PREFIX} on ${TARGET_BRANCH} (${HEAD_SHA:0:9})"

if command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}" ]; then
  gh label create security --color B60205 --description "fork-guard security findings" >/dev/null 2>&1 || true
  if URL="$(gh issue create --title "${ISSUE_TITLE}" --body-file "${ISSUE_BODY}" --label security 2>"${OUT_DIR}/issue.err")"; then
    log "opened issue: ${URL}"
    summary "### Issue opened: ${URL}"
  else
    log "could not open issue: $(cat "${OUT_DIR}/issue.err")"
    summary "### Could not open an issue — findings are in the job summary of ${RUN_URL}"
  fi
else
  summary "### gh CLI unavailable — findings are in the job summary only"
fi

summary ""
summary "**Verdict: ${VERDICT}** · reverted: ${#REVERTED[@]} · failed reverts: ${#REVERT_FAILED[@]} · manual notes: ${#MANUAL[@]}"

# fail the run: findings must never look green
exit 1
