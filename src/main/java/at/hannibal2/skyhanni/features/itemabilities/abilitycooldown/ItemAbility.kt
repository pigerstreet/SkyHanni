package at.hannibal2.skyhanni.features.itemabilities.abilitycooldown

import at.hannibal2.skyhanni.features.dungeon.DungeonApi
import at.hannibal2.skyhanni.utils.LorenzColor
import at.hannibal2.skyhanni.utils.NeuInternalName
import at.hannibal2.skyhanni.utils.NeuInternalName.Companion.toInternalName
import at.hannibal2.skyhanni.utils.NumberUtil.oneDecimal
import at.hannibal2.skyhanni.utils.NumberUtil.roundTo
import at.hannibal2.skyhanni.utils.SafeItemStack
import at.hannibal2.skyhanni.utils.ServerTimeMark
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.SkyBlockItemModifierUtils.getAbilityScrolls
import at.hannibal2.skyhanni.utils.collection.CollectionUtils.takeIfNotEmpty
import at.hannibal2.skyhanni.utils.inPartialSeconds
import at.hannibal2.skyhanni.utils.roundedUpSeconds
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The values here are checked against the item lore in the NEU repo.
 *
 * A few items state a cooldown so short that counting it down is useless (the healing wands, the fire veil wand and
 * the rogue sword all sit at 1-5s), and for those the timer counts the effect they grant instead, because that is what
 * tells the player when using the item again does something. Those entries are marked below, so their value is not
 * "corrected" to the cooldown from the lore later on.
 */
enum class ItemAbility(
    val abilityName: String,
    /** How long the item is counted down for, see the note on the class. */
    private val cooldownInSeconds: Int,
    vararg val itemNames: String,
    val alternativePosition: Boolean = false,
    val actionBarDetection: Boolean = true,
    private val ignoreMageCooldownReduction: Boolean = false,
) {
    // TODO add into repo
    // Effect: the real cooldown is 3 ticks, 5s is how long the absorption shield lasts
    WITHER_IMPACT(5, ignoreMageCooldownReduction = true),
    WITHER_SHIELD_SCROLL(10, ignoreMageCooldownReduction = true, alternativePosition = true),
    SHADOW_WARP_SCROLL(10),
    IMPLOSION_SCROLL(10),
    GYROKINETIC_WAND_LEFT(30, "GYROKINETIC_WAND", alternativePosition = true),
    GYROKINETIC_WAND_RIGHT(10, "GYROKINETIC_WAND"),
    GIANTS_SWORD(30),
    ICE_SPRAY_WAND(5, "STARRED_ICE_SPRAY_WAND"),
    RAGNAROCK_AXE(20),
    // Effect: 1s cooldown, 6s of healing. The weaker wands heal for 5s, they share this entry
    WAND_OF_ATONEMENT(6, "WAND_OF_HEALING", "WAND_OF_MENDING", "WAND_OF_RESTORATION"),
    SOS_FLARE(10),
    ALERT_FLARE(20, "WARNING_FLARE"),

    GOLEM_SWORD(3),
    END_STONE_SWORD(5), // Effect: the lore states no cooldown, 5s is how long the damage reduction lasts
    SOUL_ESOWARD(20),
    PIGMAN_SWORD(5),
    EMBER_ROD(30),
    STAFF_OF_THE_VOLCANO(30),
    STARLIGHT_WAND(2),
    VOODOO_DOLL(5),
    WEIRD_TUBA(20),
    WEIRDER_TUBA(20),
    FIRE_FREEZE_STAFF(10),
    SWORD_OF_BAD_HEALTH(5),
    WITHER_CLOAK(10),
    HOLY_ICE(4),
    VOODOO_DOLL_WILTED(3),
    FIRE_FURY_STAFF(20),
    SHADOW_FURY(15, "STARRED_SHADOW_FURY"),
    ROYAL_PIGEON(5),
    WAND_OF_STRENGTH(10), // Effect: 1s cooldown, 10s of strength
    TACTICAL_INSERTION(20),
    TOTEM_OF_CORRUPTION(20),
    ENRAGER(20),

    // doesn't have a sound
    ENDER_BOW("Ender Warp", 5, "Ender Bow"),
    LIVID_DAGGER("Throw", 5, "Livid Dagger"),
    FIRE_VEIL("Fire Veil", 5, "Fire Veil Wand"), // Effect: 1s cooldown, 5s of fire veil
    INK_WAND("Ink Bomb", 30, "Ink Wand"),
    ROGUE_SWORD("Speed Boost", 30, "Rogue Sword", ignoreMageCooldownReduction = true), // Effect: 5s cooldown, 30s of speed
    TALBOTS_THEODOLITE("Track", 10, "Talbot's Theodolite"),
    ATOMSPLIT_KATANA("Soulcry", 4, "Atomsplit Katana", "Vorpal Katana", "Voidedge Katana", ignoreMageCooldownReduction = true),
    SPIRIT_BOOTS("Spirit Glide", 60, "Spirit Boots"),
    HELLSTORM_WAND("Hellstorm", 30, "Hellstorm Wand"),
    CAT_DETECTOR("Meownar", 9, "Cat Detector"),
    SECRET_TRACKER("Echolocation", 5, "Secret Tracker"),
    DETECTIVE_SCANNER("Detect!", 4, "Detective Scanner"),
    CRYPT_WITHERLORD_SWORD("Witherlord", 3, "Crypt Witherlord Sword"),

    // doesn't have a consistent sound
    ECHO("Echo", 3, "Ancestral Spade");

    var newVariant = false
    var internalNames = mutableListOf<NeuInternalName>()

    /**
     * When the ability was used, corrected for the time the server needed to tell us about it. Everything shown is
     * derived from this absolute time mark, so the countdown does not drift away when ticks are late or dropped.
     *
     * Measured on the server clock, because Hypixel counts these cooldowns down in server ticks. A server running
     * below 20 TPS stretches them in real time, and a wall clock would report the ability as ready too early.
     */
    var activationStart: ServerTimeMark = ServerTimeMark.farPast()
        private set

    /**
     * End of the phase the ability is currently in. For most abilities that is the whole cooldown, but some run
     * through several phases: a cast time, then the active effect, then the rest of the cooldown.
     */
    private var phaseEnd: ServerTimeMark = ServerTimeMark.farPast()

    private var phaseColor: LorenzColor? = null

    /**
     * The last time the player clicked while holding an item with this ability, on the real clock. Matching a sound
     * to the click that caused it is about how long the packets were under way, which server ticks say nothing about.
     */
    var lastItemClick: SimpleTimeMark = SimpleTimeMark.farPast()
        private set

    /** The same click as [lastItemClick] on the server clock, so it can be used as the start of a cooldown. */
    var lastItemClickOnServer: ServerTimeMark = ServerTimeMark.farPast()
        private set

    constructor(
        cooldownInSeconds: Int,
        vararg alternateInternalNames: String,
        alternativePosition: Boolean = false,
        ignoreMageCooldownReduction: Boolean = false,
    ) : this(
        "no name",
        cooldownInSeconds,
        actionBarDetection = false,
        alternativePosition = alternativePosition,
        ignoreMageCooldownReduction = ignoreMageCooldownReduction,
    ) {
        newVariant = true
        alternateInternalNames.forEach {
            internalNames.add(it.toInternalName())
        }
        internalNames.add(name.toInternalName())
    }

    /**
     * Whether the colored phases of this ability run inside its regular cooldown. Those items only become usable
     * again [getCooldown] after they were used, no matter how long casting and the effect itself take.
     */
    private val phasesInsideCooldown: Boolean get() = this == GYROKINETIC_WAND_RIGHT || this == RAGNAROCK_AXE

    /** The moment the ability can be used again. */
    val cooldownEnd: ServerTimeMark
        get() = if (phasesInsideCooldown) maxOf(phaseEnd, activationStart + getCooldown()) else phaseEnd

    /**
     * What the countdown counts down to: the end of the current phase while the ability is in one (that is the
     * casting time or how long the effect still lasts), and the end of the cooldown afterwards.
     */
    private val displayEnd: ServerTimeMark get() = if (phaseEnd.isInFuture()) phaseEnd else cooldownEnd

    /** The color of the phase the ability is currently in, or null once that phase is over. */
    val activePhaseColor: LorenzColor? get() = phaseColor.takeIf { phaseEnd.isInFuture() }

    /**
     * Starts the cooldown. [start] should be the moment the server started it, not the moment we noticed it, so that
     * the countdown stays correct on a laggy connection.
     */
    fun activate(
        color: LorenzColor? = null,
        duration: Duration = getCooldown(),
        start: ServerTimeMark = ServerTimeMark.now(),
    ) {
        activationStart = start
        enterPhase(color, duration, start)
    }

    /** Moves an already running ability into its next phase, without changing when it was originally used. */
    fun enterPhase(color: LorenzColor?, duration: Duration, start: ServerTimeMark = ServerTimeMark.now()) {
        phaseColor = color
        phaseEnd = start + duration
    }

    fun reset() {
        activationStart = ServerTimeMark.farPast()
        phaseEnd = ServerTimeMark.farPast()
        phaseColor = null
        // A click from before the world switch must not be matched to a sound from after it.
        lastItemClick = SimpleTimeMark.farPast()
        lastItemClickOnServer = ServerTimeMark.farPast()
    }

    fun isOnCooldown(): Boolean = cooldownEnd.isInFuture()

    /** The time the countdown shows, see [displayEnd]. */
    fun getRemaining(): Duration = displayEnd.timeUntil()

    /**
     * Added on top of the cooldown, for abilities whose cooldown does not start when they are used.
     *
     * The countdown is anchored to the click, which is right for an ability that goes on cooldown the moment the
     * server sees it. The ragnarock axe first channels for three seconds, and measuring it in game puts the moment it
     * can be used again about two seconds after the countdown reaches zero, so the server looks to start counting
     * once that channel resolves rather than when the click lands.
     *
     * Kept out of [cooldownInSeconds], which matches the item lore and is correct as it stands. Keeping the
     * correction separate leaves the stated cooldown readable and this one number easy to adjust.
     */
    private val cooldownStartDelay: Duration
        get() = when (this) {
            RAGNAROCK_AXE -> 2.seconds
            else -> Duration.ZERO
        }

    fun getCooldown(): Duration {
        // The wand of atonement isn't really a cooldown but an effect over time, so don't apply cooldown multipliers
        if (this == WAND_OF_ATONEMENT) return cooldownInSeconds.seconds

        // The delay is added after the multiplier: it is a late start, not part of the cooldown being reduced.
        return cooldownInSeconds.seconds * getMultiplier() + cooldownStartDelay
    }

    fun getDurationText(): String {
        val duration = getRemaining()
        return if (duration < 1.6.seconds) {
            duration.inPartialSeconds.roundTo(1).oneDecimal()
        } else {
            duration.roundedUpSeconds.toString()
        }
    }

    fun setItemClick() {
        lastItemClick = SimpleTimeMark.now()
        lastItemClickOnServer = ServerTimeMark.now()
    }

    companion object {

        private val WITHER_SCROLLS = setOf(WITHER_SHIELD_SCROLL, SHADOW_WARP_SCROLL, IMPLOSION_SCROLL)

        fun getAllAbilityScrolls(itemStack: SafeItemStack?): Set<ItemAbility> =
            itemStack?.getAbilityScrolls()?.takeIfNotEmpty()?.getAllAbilityScrolls().orEmpty()

        private fun List<NeuInternalName>.getAllAbilityScrolls(): Set<ItemAbility> = WITHER_SCROLLS
            .filter { ability -> ability.internalNames.any { it in this } }
            .toMutableSet()
            .apply {
                if (size == 3) {
                    clear()
                    add(WITHER_IMPACT)
                }
            }

        fun ItemAbility.getMultiplier(): Double {
            return getMageCooldownReduction() ?: 1.0
        }

        private fun ItemAbility.getMageCooldownReduction(): Double? {
            if (ignoreMageCooldownReduction) return null
            if (!DungeonApi.inDungeon()) return null
            if (DungeonApi.playerClass != DungeonApi.DungeonClass.MAGE) return null

            var abilityCooldownMultiplier = 1.0
            abilityCooldownMultiplier -= if (DungeonApi.isUniqueClass) {
                0.5 // 50% base reduction at level 0
            } else {
                0.25 // 25% base reduction at level 0
            }

            // 1% ability reduction every other level
            abilityCooldownMultiplier -= 0.01 * floor(DungeonApi.playerClassLevel / 2f)

            return abilityCooldownMultiplier
        }
    }
}
