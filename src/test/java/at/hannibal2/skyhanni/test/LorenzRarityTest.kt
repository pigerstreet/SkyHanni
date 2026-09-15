package at.hannibal2.skyhanni.test

import at.hannibal2.skyhanni.utils.LorenzRarity
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class LorenzRarityTest {

    @Test
    fun `there is no rarity below the lowest one`() {
        // getById(-1) used to pass its upper bound check and index entries[-1].
        Assertions.assertNull(LorenzRarity.COMMON.oneBelow(logError = false))
    }

    @Test
    fun `there is no rarity above the highest one`() {
        Assertions.assertNull(LorenzRarity.entries.last().oneAbove(logError = false))
    }

    @Test
    fun `neighbouring rarities are found`() {
        Assertions.assertEquals(LorenzRarity.UNCOMMON, LorenzRarity.RARE.oneBelow(logError = false))
        Assertions.assertEquals(LorenzRarity.EPIC, LorenzRarity.RARE.oneAbove(logError = false))
    }

    @Test
    fun `out of range ids return null`() {
        Assertions.assertNull(LorenzRarity.getById(-1))
        Assertions.assertNull(LorenzRarity.getById(LorenzRarity.entries.size))
    }
}
