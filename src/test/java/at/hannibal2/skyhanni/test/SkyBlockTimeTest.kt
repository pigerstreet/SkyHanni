package at.hannibal2.skyhanni.test

import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.SkyBlockTime
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SkyBlockTimeTest {

    private fun at(millisSinceEpoch: Long) =
        SkyBlockTime.fromTimeMark(SimpleTimeMark(SkyBlockTime.SKYBLOCK_EPOCH_START_MILLIS + millisSinceEpoch))

    @Test
    fun `minute and second stay inside their range for every millisecond of an hour`() {
        for (offset in 0 until SkyBlockTime.SKYBLOCK_HOUR_MILLIS) {
            val time = at(offset)
            Assertions.assertTrue(time.minute in 0..59, "minute was ${time.minute} at offset $offset")
            Assertions.assertTrue(time.second in 0..59, "second was ${time.second} at offset $offset")
        }
    }

    @Test
    fun `the last milliseconds of an hour are still minute 59`() {
        // The final 20 ms of every hour used to divide out to minute 60, which the scoreboard clock renders.
        for (offset in (SkyBlockTime.SKYBLOCK_HOUR_MILLIS - 20) until SkyBlockTime.SKYBLOCK_HOUR_MILLIS) {
            val time = at(offset)
            Assertions.assertEquals(0, time.hour, "hour at offset $offset")
            Assertions.assertEquals(59, time.minute, "minute at offset $offset")
        }
        Assertions.assertEquals(1, at(SkyBlockTime.SKYBLOCK_HOUR_MILLIS).hour)
    }

    @Test
    fun `converting to millis and back keeps the same time`() {
        val time = SkyBlockTime(year = 3, month = 7, day = 21, hour = 13, minute = 42, second = 17)
        Assertions.assertEquals(time, SkyBlockTime.fromTimeMark(time.toTimeMark()))
    }
}
