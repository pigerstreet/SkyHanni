package at.hannibal2.skyhanni.features.itemabilities.abilitycooldown

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.ConfigUpdaterMigrator
import at.hannibal2.skyhanni.data.model.SkyblockStat
import at.hannibal2.skyhanni.events.ActionBarUpdateEvent
import at.hannibal2.skyhanni.events.ItemClickEvent
import at.hannibal2.skyhanni.events.PlaySoundEvent
import at.hannibal2.skyhanni.events.RenderGuiItemOverlayEvent
import at.hannibal2.skyhanni.events.RenderItemTipEvent
import at.hannibal2.skyhanni.events.RenderObject
import at.hannibal2.skyhanni.events.chat.SkyHanniChatEvent
import at.hannibal2.skyhanni.events.minecraft.SkyHanniTickEvent
import at.hannibal2.skyhanni.features.nether.ashfang.AshfangFreezeCooldown
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.CachedItemData.Companion.cachedData
import at.hannibal2.skyhanni.utils.InventoryUtils
import at.hannibal2.skyhanni.utils.InventoryUtils.recentlyHeld
import at.hannibal2.skyhanni.utils.ItemUtils
import at.hannibal2.skyhanni.utils.ItemUtils.cleanName
import at.hannibal2.skyhanni.utils.ItemUtils.getInternalName
import at.hannibal2.skyhanni.utils.LorenzColor
import at.hannibal2.skyhanni.utils.NeuInternalName.Companion.toInternalName
import at.hannibal2.skyhanni.utils.NumberUtil.roundTo
import at.hannibal2.skyhanni.utils.RegexUtils.matchMatcher
import at.hannibal2.skyhanni.utils.RenderUtils.highlight
import at.hannibal2.skyhanni.utils.SafeItemStack
import at.hannibal2.skyhanni.utils.ServerPingUtils
import at.hannibal2.skyhanni.utils.ServerTimeMark
import at.hannibal2.skyhanni.utils.SkyBlockItemModifierUtils.getItemId
import at.hannibal2.skyhanni.utils.SkyBlockItemModifierUtils.getItemUuid
import at.hannibal2.skyhanni.utils.SkyBlockUtils
import at.hannibal2.skyhanni.utils.collection.CollectionUtils.equalsOneOf
import at.hannibal2.skyhanni.utils.collection.CollectionUtils.mapKeysNotNull
import at.hannibal2.skyhanni.utils.compat.MinecraftCompat
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@SkyHanniModule
object ItemAbilityCooldown {

    private val config get() = SkyHanniMod.feature.inventory.itemAbilities

    private val patternGroup = RepoPattern.group("item.abilities.cooldown")
    private val youAlignedOthersPattern by patternGroup.pattern(
        "alignedother",
        "§eYou aligned §r§a.* §r§eother players?!",
    )
    private val youBuffedYourselfPattern by patternGroup.pattern(
        "buffedyourself",
        "§aYou buffed yourself for §r§c\\+\\d+${SkyblockStat.STRENGTH.hypixelIcon} Strength",
    )

    /**
     * REGEX-TEST: §63,848/3,473❤     §b-24 Mana (§6Instant Transmission§b)     §b2,507/2,507✎ Mana
     */
    private val abilityUsePattern by patternGroup.pattern(
        "abilityuse",
        ".*§b-\\d+ Mana \\(§6(?<type>.*)§b\\).*",
    )

    /**
     * How long the ragnarock axe is charging up before the strength buff starts.
     *
     * The lore says 3s. The extra 300ms is a safety margin: the countdown is anchored to the click, while the server
     * only starts charging once that click reaches it, so without it the cast phase reads as finished slightly before
     * the strength actually lands.
     */
    private val RAGNAROCK_CAST_TIME = 3.seconds + 600.milliseconds

    /** How long the strength buff of the ragnarock axe lasts once it finished casting. */
    private val RAGNAROCK_BUFF_TIME = 10.seconds

    private var lastAbility = ""
    private var items = mapOf<String, List<ItemAbility>>()
    private var abilityItems = mapOf<SafeItemStack, MutableList<ItemAbility>>()
    private val WEIRD_TUBA = "WEIRD_TUBA".toInternalName()
    private val WEIRDER_TUBA = "WEIRDER_TUBA".toInternalName()
    private val VOODOO_DOLL = "VOODOO_DOLL".toInternalName()
    private val VOODOO_DOLL_WILTED = "VOODOO_DOLL_WILTED".toInternalName()
    private val WARNING_FLARE = "WARNING_FLARE".toInternalName()
    private val ALERT_FLARE = "ALERT_FLARE".toInternalName()
    private val SOS_FLARE = "SOS_FLARE".toInternalName()
    private val TOTEM_OF_CORRUPTION = "TOTEM_OF_CORRUPTION".toInternalName()

    @HandleEvent
    fun onPlaySound(event: PlaySoundEvent) {
        if (!isEnabled()) return
        when {
            // Wither Shield Sound Solo and Wither Impact.
            // The volume is not part of the check: the solo scroll plays this at 1.0, but wither impact on a blade
            // holding all three scrolls plays the very same sound at 0.45. Which of the two it was is decided by the
            // scrolls on the held item below, and a stray sound cannot start a cooldown without a matching click.
            event.soundName == "entity.zombie_villager.cure" && event.pitch == 0.6984127f -> {
                val scrolls = ItemAbility.getAllAbilityScrolls(InventoryUtils.getItemInHand())
                if (scrolls.singleOrNull() == ItemAbility.WITHER_IMPACT) {
                    ItemAbility.WITHER_IMPACT.sound()
                } else if (scrolls.contains(ItemAbility.WITHER_SHIELD_SCROLL)) {
                    // This is the wither shield sound only. The other scrolls on the item have their own sounds.
                    ItemAbility.WITHER_SHIELD_SCROLL.sound()
                }
            }

            // Shadow Warp, volume left out for the same reason as the wither shield sound above.
            event.soundName == "block.lava.extinguish" && event.pitch == 0.4920635f -> {
                val scrolls = ItemAbility.getAllAbilityScrolls(InventoryUtils.getItemInHand())
                if (scrolls.contains(ItemAbility.SHADOW_WARP_SCROLL)) {
                    ItemAbility.SHADOW_WARP_SCROLL.sound()
                }
            }
            // Fire Fury Staff
            event.soundName == "block.lava.pop" && event.pitch == 1f && event.volume == 1f -> {
                ItemAbility.FIRE_FURY_STAFF.sound()
            }
            // Ice Spray Wand
            event.soundName == "entity.ender_dragon.growl" && event.pitch == 1f && event.volume == 1f -> {
                ItemAbility.ICE_SPRAY_WAND.sound()
            }
            // Gyrokinetic Wand & Shadow Fury
            event.soundName == "entity.enderman.teleport" -> {
                // Gryokinetic Wand
                if (event.pitch == 0.61904764f && event.volume == 1f) {
                    ItemAbility.GYROKINETIC_WAND_LEFT.sound()
                }
                // Shadow Fury
                val internalName = InventoryUtils.getItemInHand()?.getInternalName() ?: return
                if (!internalName.equalsOneOf(
                        "SHADOW_FURY".toInternalName(),
                        "STARRED_SHADOW_FURY".toInternalName(),
                    )
                ) return

                ItemAbility.SHADOW_FURY.sound()
            }
            // Giant's Sword
            event.soundName == "block.anvil.land" && event.pitch == 0.4920635f && event.volume == 0.5f -> {
                ItemAbility.GIANTS_SWORD.sound()
            }
            // Atomsplit Katana
            event.soundName == "entity.ghast.ambient" && event.pitch == 0.4920635f && event.volume == 0.15f -> {
                ItemAbility.ATOMSPLIT_KATANA.sound()
            }
            // Wand of Atonement
            event.soundName == "block.lava.pop" && event.pitch == 0.7619048f && event.volume == 0.15f -> {
                ItemAbility.WAND_OF_ATONEMENT.sound()
            }
            // Starlight Wand
            event.soundName == "entity.bat.hurt" && event.volume == 0.1f -> {
                ItemAbility.STARLIGHT_WAND.sound()
            }
            // Voodoo Doll
            event.soundName == "entity.ghast.hurt" && event.volume == 1f && event.pitch >= 1.6 && event.pitch <= 1.7 -> {
                if (VOODOO_DOLL.recentlyHeld()) {
                    ItemAbility.VOODOO_DOLL.sound()
                } else if (VOODOO_DOLL_WILTED.recentlyHeld()) {
                    ItemAbility.VOODOO_DOLL_WILTED.sound()
                }
            }
            // Golem Sword & Implosion Solo Scroll & Staff of the Volcano
            event.soundName == "entity.generic.explode" -> {
                // Volume left out for the same reason as the wither shield sound above: the solo scroll plays
                // this at 1.0 and wither impact at 0.75.
                if (event.pitch == 1f) {
                    val scrolls = ItemAbility.getAllAbilityScrolls(InventoryUtils.getItemInHand())
                    if (scrolls.contains(ItemAbility.IMPLOSION_SCROLL)) {
                        ItemAbility.IMPLOSION_SCROLL.sound()
                    }
                }
                if (event.pitch == 4.047619f && event.volume == 0.2f) {
                    ItemAbility.GOLEM_SWORD.sound()
                }
                if (event.pitch == 0.4920635f && event.volume == 0.5f) {
                    ItemAbility.STAFF_OF_THE_VOLCANO.sound()
                }
            }
            // Weird Tuba & Weirder Tuba
            event.soundName == "entity.wolf.death" && event.volume == 0.5f -> {
                if (WEIRD_TUBA.recentlyHeld()) {
                    ItemAbility.WEIRD_TUBA.sound()
                }
                if (WEIRDER_TUBA.recentlyHeld()) {
                    ItemAbility.WEIRDER_TUBA.sound()
                }
            }
            // End Stone Sword
            event.soundName == "entity.zombie_villager.converted" && event.pitch == 2f && event.volume == 0.3f -> {
                ItemAbility.END_STONE_SWORD.sound()
            }
            // Soul Esoward
            event.soundName == "entity.wolf.pant" && event.pitch == 1.3968254f && event.volume == 0.4f -> {
                ItemAbility.SOUL_ESOWARD.sound()
            }
            // Pigman Sword
            event.soundName == "entity.piglin.angry" && event.pitch == 2f && event.volume == 0.3f -> {
                ItemAbility.PIGMAN_SWORD.sound()
            }
            // Ember Rod
            event.soundName == "entity.ghast.shoot" && event.pitch == 1f && event.volume == 0.3f -> {
                ItemAbility.EMBER_ROD.sound()
            }
            // Fire Freeze Staff
            event.soundName == "entity.elder_guardian.ambient" && event.pitch == 2f && event.volume == 0.2f -> {
                ItemAbility.FIRE_FREEZE_STAFF.sound()
            }
            // Staff of the Volcano
            event.soundName == "entity.generic.eat" && event.pitch == 1f && event.volume == 1f -> {
                ItemAbility.STAFF_OF_THE_VOLCANO.sound()
            }
            // Holy Ice
            event.soundName == "entity.generic.drink" && event.pitch.roundTo(1) == 1.8f && event.volume == 1f -> {
                ItemAbility.HOLY_ICE.sound()
            }
            // Royal Pigeon
            event.soundName == "entity.bat.ambient" && event.pitch == 0.4920635f && event.volume == 1f -> {
                ItemAbility.ROYAL_PIGEON.sound()
            }
            // Wand of Strength
            event.soundName == "entity.generic.eat" && event.pitch == 0.4920635f && event.volume == 1f -> {
                ItemAbility.WAND_OF_STRENGTH.sound()
            }
            // Tactical Insertion
            event.soundName == "item.flintandsteel.use" && event.pitch == 0.74603176f && event.volume == 1f -> {
                val ability = ItemAbility.TACTICAL_INSERTION
                ability.activate(LorenzColor.DARK_PURPLE, 3.seconds, ability.startedAt())
            }

            event.soundName == "entity.zombie_villager.cure" && event.pitch == 1.8888888f && event.volume == 0.7f -> {
                val ability = ItemAbility.TACTICAL_INSERTION
                ability.activate(null, 17.seconds, ability.startedAt())
            }
            // Totem of Corruption
            event.soundName == "block.lever.click" && event.pitch == 0.84126985f && event.volume == 0.5f -> {
                if (TOTEM_OF_CORRUPTION.recentlyHeld()) {
                    ItemAbility.TOTEM_OF_CORRUPTION.sound()
                }
            }
            // Enrager
            event.soundName == "entity.ender_dragon.growl" && event.pitch == 0.4920635f && event.volume == 2f -> {
                ItemAbility.ENRAGER.sound()
            }

            // Blaze Slayer Flares
            event.soundName == "entity.firework_rocket.launch" && event.pitch == 1f && event.volume == 3f -> {
                if (WARNING_FLARE.recentlyHeld() || ALERT_FLARE.recentlyHeld()) {
                    ItemAbility.ALERT_FLARE.sound()
                }
                if (SOS_FLARE.recentlyHeld()) {
                    ItemAbility.SOS_FLARE.sound()
                }
            }
        }
    }

    @HandleEvent
    fun onItemClick(event: ItemClickEvent) {
        if (AshfangFreezeCooldown.isCurrentlyFrozen()) return
        handleItemClick(event.itemInHand)
    }

    private fun handleItemClick(itemInHand: SafeItemStack?) {
        if (!isEnabled()) return
        val stack = itemInHand ?: return
        // Items can hold more than one ability (the gyrokinetic wand has one per mouse button), so remember the
        // click for every single one of them.
        for (ability in hasAbility(stack)) {
            ability.setItemClick()
        }
    }

    @HandleEvent
    fun onWorldChange() {
        for (ability in ItemAbility.entries) {
            ability.reset()
        }
    }

    @HandleEvent
    fun onActionBarUpdate(event: ActionBarUpdateEvent) {
        if (!isEnabled()) return

        val message: String = event.actionBar
        handleOldAbilities(message)

        // The ragnarock axe runs through three phases instead of a single cooldown: it charges up, then gives the
        // strength buff, and only after that the rest of its cooldown is left. The action bar tells us about every
        // phase change, so we follow it instead of guessing when a phase is over.
        val axe = ItemAbility.RAGNAROCK_AXE
        when {
            message.contains("§lCASTING IN ") -> {
                if (axe.activePhaseColor != LorenzColor.WHITE) {
                    axe.activate(LorenzColor.WHITE, RAGNAROCK_CAST_TIME, axe.startedAt())
                }
            }

            message.contains("§lCASTING") -> {
                if (axe.activePhaseColor != LorenzColor.DARK_PURPLE) {
                    axe.enterCooldownPhase(LorenzColor.DARK_PURPLE, RAGNAROCK_BUFF_TIME, RAGNAROCK_CAST_TIME)
                }
            }

            message.contains("§c§lCANCELLED") -> {
                // The cast was interrupted, but the axe still goes on its full cooldown.
                axe.enterCooldownPhase(null, Duration.ZERO, RAGNAROCK_CAST_TIME)
            }
        }
    }

    /**
     * Starts the next phase of an ability that is already running. When we somehow missed the phases before this one,
     * the ability is started as if it had been used [alreadyElapsed] ago, so the total cooldown still adds up.
     */
    private fun ItemAbility.enterCooldownPhase(color: LorenzColor?, duration: Duration, alreadyElapsed: Duration) {
        val start = serverEventTime()
        if (isOnCooldown()) {
            enterPhase(color, duration, start)
        } else {
            activate(color, duration + alreadyElapsed, start - alreadyElapsed)
        }
    }

    private fun handleOldAbilities(message: String) {
        abilityUsePattern.matchMatcher(message) {
            val name = group("type")
            if (name == lastAbility) return
            lastAbility = name
            for (ability in ItemAbility.entries) {
                if (ability.abilityName == name) {
                    click(ability)
                    return
                }
            }
            return
        }
        lastAbility = ""
    }

    private fun isEnabled(): Boolean = SkyBlockUtils.inSkyBlock && config.itemAbilityCooldown

    private fun click(ability: ItemAbility) {
        if (ability.actionBarDetection) {
            ability.activate(start = ability.startedAt())
        }
    }

    /**
     * How far a click and the server's answer to it may be apart to still count as the same ability use. Anything
     * we do only reaches the server after half a round trip, and its answer takes just as long to come back, so on a
     * laggy connection this has to be a lot more generous than on a good one.
     */
    private val clickWindow: Duration get() = (ServerPingUtils.ping + 500.milliseconds).coerceIn(1.seconds, 3.seconds)

    /** The moment the server sent what we are reacting to, instead of the moment it arrived here. */
    private fun serverEventTime(): ServerTimeMark = ServerTimeMark.now() - ServerPingUtils.ping / 2

    /**
     * When the cooldown of this ability really started.
     *
     * The click that used the ability is the best answer whenever we can still match one to it: the server started
     * the cooldown half a round trip after that click, and the next click needs the same half round trip to get
     * there, so from the player's point of view the cooldown runs from the moment they pressed the button.
     */
    private fun ItemAbility.startedAt(): ServerTimeMark =
        if (lastItemClick.passedSince() < clickWindow) lastItemClickOnServer else serverEventTime()

    @HandleEvent
    fun onTick(event: SkyHanniTickEvent) {
        if (!isEnabled()) return

        checkHotBar(event.isMod(10))
    }

    private fun checkHotBar(recheckInventorySlots: Boolean = false) {
        if (!recheckInventorySlots && abilityItems.isNotEmpty()) return

        abilityItems = ItemUtils.getItemsInInventory(true).associateWith { hasAbility(it) }
        items = abilityItems.entries.associateByTo(
            mutableMapOf(),
            { it.key.getIdentifier() },
            { it.value },
        ).mapKeysNotNull { it.key }
    }

    /**
     * Built while rendering instead of once per tick, so the countdown keeps ticking down smoothly and stays right
     * even when the client skips ticks.
     */
    private fun ItemAbility.createItemText(): ItemText {
        if (!isOnCooldown()) {
            val readyText = if (config.itemAbilityShowWhenReady) "R" else ""
            return ItemText(LorenzColor.GREEN, readyText, false, alternativePosition)
        }
        val remaining = getRemaining()
        val color = activePhaseColor ?: if (remaining < 600.milliseconds) LorenzColor.RED else LorenzColor.YELLOW
        return ItemText(color, getDurationText(), true, alternativePosition)
    }

    @HandleEvent
    fun onRenderItemTip(event: RenderItemTipEvent) {
        if (!isEnabled()) return

        val stack = event.stack

        val guiOpen = MinecraftCompat.screen != null
        val uuid = stack.getIdentifier() ?: return
        val list = items[uuid] ?: return

        for (ability in list) {
            val itemText = ability.createItemText()
            if (guiOpen && !itemText.onCooldown) continue
            if (itemText.text.isEmpty()) continue
            val color = itemText.color
            val renderObject = RenderObject(color.getChatColor() + itemText.text)
            if (itemText.alternativePosition) {
                renderObject.offsetX = -8
                renderObject.offsetY = -10
            }
            event.renderObjects.add(renderObject)
        }
    }

    @HandleEvent
    fun onRenderItem(event: RenderGuiItemOverlayEvent) {
        if (!isEnabled()) return
        if (!config.itemAbilityCooldownBackground) return

        val guiOpen = MinecraftCompat.screen != null
        val stack = event.stack

        val uuid = stack?.getIdentifier() ?: return
        val list = items[uuid] ?: return

        for (ability in list) {
            val itemText = ability.createItemText()
            if (guiOpen && !itemText.onCooldown) continue
            val color = itemText.color

            // fix multiple problems when having multiple abilities
            var opacity = 130
            if (color == LorenzColor.GREEN) {
                opacity = 80
                // Skip only this ability, the other one on the same item can still be on cooldown.
                if (!config.itemAbilityShowWhenReady) continue
            }
            event.highlight(color.addOpacity(opacity))
        }
    }

    private fun SafeItemStack.getIdentifier(): String? =
        cachedData.identifier ?: fetchIdentifier().also { cachedData.identifier = it }

    private fun SafeItemStack.fetchIdentifier() = getItemUuid() ?: getItemId()


    @HandleEvent
    fun onChat(event: SkyHanniChatEvent.Allow) {
        if (!isEnabled()) return

        val message = event.message
        if (message == "§dCreeper Veil §r§aActivated!") {
            ItemAbility.WITHER_CLOAK.activate(LorenzColor.LIGHT_PURPLE, start = ItemAbility.WITHER_CLOAK.startedAt())
        }
        if (message == "§dCreeper Veil §r§cDe-activated! §r§8(Expired)" ||
            message == "§cNot enough mana! §r§dCreeper Veil §r§cDe-activated!"
        ) {
            ItemAbility.WITHER_CLOAK.activate(start = serverEventTime())
        }
        if (message == "§dCreeper Veil §r§cDe-activated!") {
            ItemAbility.WITHER_CLOAK.activate(null, 5.seconds, serverEventTime())
        }

        youAlignedOthersPattern.matchMatcher(message) {
            alignGyrokineticWand()
        }
        if (message == "§eYou §r§aaligned §r§eyourself!") {
            alignGyrokineticWand()
        }
        if (message == "§cRagnarock was cancelled due to being hit!") {
            ItemAbility.RAGNAROCK_AXE.enterCooldownPhase(null, Duration.ZERO, RAGNAROCK_CAST_TIME)
        }
        youBuffedYourselfPattern.matchMatcher(message) {
            ItemAbility.SWORD_OF_BAD_HEALTH.activate(start = ItemAbility.SWORD_OF_BAD_HEALTH.startedAt())
        }
    }

    /**
     * The right click of the gyrokinetic wand aligns for 6 seconds, and only after that the rest of its own cooldown
     * is left. The left click has its own, separate cooldown on the very same item.
     */
    private fun alignGyrokineticWand() {
        val ability = ItemAbility.GYROKINETIC_WAND_RIGHT
        ability.activate(LorenzColor.BLUE, 6.seconds, ability.startedAt())
    }

    @HandleEvent
    fun onConfigFix(event: ConfigUpdaterMigrator.ConfigFixEvent) {
        event.move(31, "itemAbilities", "inventory.itemAbilities")
    }

    // TODO add item caching
    private fun hasAbility(stack: SafeItemStack): MutableList<ItemAbility> {
        val itemName: String = stack.cleanName
        val internalName = stack.getInternalName()
        val scrolls = ItemAbility.getAllAbilityScrolls(stack)

        val list = mutableListOf<ItemAbility>()
        list.addAll(scrolls)
        for (ability in ItemAbility.entries) {
            if (ability.newVariant) {
                if (ability.internalNames.contains(internalName)) {
                    list.add(ability)
                }
            } else {
                for (name in ability.itemNames) {
                    if (itemName.contains(name)) {
                        list.add(ability)
                    }
                }
            }
        }
        return list
    }

    /**
     * Ability sounds are also played for other players around us, so we only count a sound as our own ability when we
     * clicked the matching item shortly before. That click is also where the cooldown starts.
     */
    private fun ItemAbility.sound() {
        if (lastItemClick.passedSince() > clickWindow) return
        activate(start = lastItemClickOnServer)
    }

    class ItemText(
        val color: LorenzColor,
        val text: String,
        val onCooldown: Boolean,
        val alternativePosition: Boolean,
    )
}
