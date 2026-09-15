package at.hannibal2.skyhanni.test

import at.hannibal2.skyhanni.utils.TimeUtils
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TimeUtilsTest {

    @Test
    fun `colon separated durations are parsed`() {
        Assertions.assertEquals(1.hours + 2.minutes + 3.seconds, TimeUtils.getDurationOrNull("1:02:03"))
        Assertions.assertEquals(4.minutes + 5.seconds, TimeUtils.getDurationOrNull("04:05"))
        Assertions.assertEquals(30.seconds, TimeUtils.getDurationOrNull("30"))
    }

    @Test
    fun `suffixed durations are parsed`() {
        Assertions.assertEquals(10.seconds, TimeUtils.getDurationOrNull("10s"))
        Assertions.assertEquals(2.hours + 30.minutes, TimeUtils.getDurationOrNull("2h30m"))
    }

    @Test
    fun `unparsable input returns null instead of throwing`() {
        // Callers such as the /shtitle command rely on null here to show their own error message.
        Assertions.assertNull(TimeUtils.getDurationOrNull("abc"))
        Assertions.assertNull(TimeUtils.getDurationOrNull("10x"))
        Assertions.assertNull(TimeUtils.getDurationOrNull("1:2:3:4"))
        Assertions.assertNull(TimeUtils.getDurationOrNull("1:ab"))
    }

    @Test
    fun `large hour counts do not overflow`() {
        Assertions.assertEquals(1000.hours, TimeUtils.getDurationOrNull("1000:00:00"))
    }
}
