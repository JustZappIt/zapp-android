package co.electriccoin.zcash.ui.screen.invest

import co.electriccoin.zcash.ui.screen.invest.common.UsMarketHours
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsMarketHoursTest {
    private fun ny(
        day: Int,
        hour: Int,
        minute: Int = 0,
    ) = ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, ZoneId.of("America/New_York")).toInstant()

    @Test
    fun `regular session is weekdays 09_30 to 16_00 in New York`() {
        assertFalse(UsMarketHours.isRegularSession(ny(28, 9, 29)))
        assertTrue(UsMarketHours.isRegularSession(ny(28, 9, 30)))
        assertTrue(UsMarketHours.isRegularSession(ny(28, 15, 59)))
        assertFalse(UsMarketHours.isRegularSession(ny(28, 16, 0)))
        assertFalse(UsMarketHours.isRegularSession(ny(26, 12))) // Saturday
    }

    @Test
    fun `the next open skips the weekend`() {
        assertEquals(ny(28, 9, 30), UsMarketHours.nextRegularOpen(ny(25, 17))) // Friday evening → Monday
        assertEquals(ny(28, 9, 30), UsMarketHours.nextRegularOpen(ny(28, 8))) // Monday morning → same day
    }

    @Test
    fun `the 24-5 window runs Sunday 20_00 to Friday 20_00`() {
        assertFalse(UsMarketHours.isWeekdayWindowOpen(ny(27, 19, 59)))
        assertTrue(UsMarketHours.isWeekdayWindowOpen(ny(27, 20)))
        assertTrue(UsMarketHours.isWeekdayWindowOpen(ny(25, 19, 59)))
        assertFalse(UsMarketHours.isWeekdayWindowOpen(ny(25, 20)))
        assertEquals(ny(27, 20), UsMarketHours.nextWeekdayWindowOpen(ny(26, 12)))
    }
}
