package co.electriccoin.zcash.ui.screen.invest.common

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * US market times, for the "prices can be patchy" banner and the Weekdays group's status. Holidays are not
 * modelled: on one the banner stays hidden while prices are thin, which only means one missing hint.
 */
internal object UsMarketHours {
    private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    private val REGULAR_OPEN: LocalTime = LocalTime.parse("09:30")
    private val REGULAR_CLOSE: LocalTime = LocalTime.parse("16:00")

    /** Ondo's 24/5 window opens Sunday 20:00 and closes Friday 20:00, US Eastern. */
    private val WEEKDAY_WINDOW_EDGE: LocalTime = LocalTime.parse("20:00")

    /** Monday to Friday, 09:30 to 16:00 US Eastern. */
    fun isRegularSession(now: Instant): Boolean {
        val ny = now.atZone(NEW_YORK)
        return ny.dayOfWeek.isWeekday() && !ny.toLocalTime().isBefore(REGULAR_OPEN) &&
            ny.toLocalTime().isBefore(REGULAR_CLOSE)
    }

    /** The next regular-session open at or after [now]; [now]'s own session if it is open. */
    fun nextRegularOpen(now: Instant): Instant {
        var day = now.atZone(NEW_YORK).toLocalDate()
        repeat(DAYS_TO_SCAN) {
            val open = ZonedDateTime.of(day, REGULAR_OPEN, NEW_YORK)
            val close = ZonedDateTime.of(day, REGULAR_CLOSE, NEW_YORK)
            if (day.dayOfWeek.isWeekday() && now.isBefore(close.toInstant())) {
                return if (now.isBefore(open.toInstant())) open.toInstant() else now
            }
            day = day.plusDays(1)
        }
        error("no weekday within $DAYS_TO_SCAN days")
    }

    /** Whether Ondo's weekday names are inside their 24/5 window (Sunday 20:00 to Friday 20:00 US Eastern). */
    fun isWeekdayWindowOpen(now: Instant): Boolean {
        val ny = now.atZone(NEW_YORK)
        val time = ny.toLocalTime()
        return when (ny.dayOfWeek) {
            DayOfWeek.SATURDAY -> false
            DayOfWeek.SUNDAY -> !time.isBefore(WEEKDAY_WINDOW_EDGE)
            DayOfWeek.FRIDAY -> time.isBefore(WEEKDAY_WINDOW_EDGE)
            else -> true
        }
    }

    /** When the 24/5 window next opens (Sunday 20:00 US Eastern); [now] itself while it is open. */
    fun nextWeekdayWindowOpen(now: Instant): Instant {
        if (isWeekdayWindowOpen(now)) return now
        var day = now.atZone(NEW_YORK).toLocalDate()
        while (day.dayOfWeek != DayOfWeek.SUNDAY) day = day.plusDays(1)
        return ZonedDateTime.of(day, WEEKDAY_WINDOW_EDGE, NEW_YORK).toInstant()
    }

    private fun DayOfWeek.isWeekday() = this != DayOfWeek.SATURDAY && this != DayOfWeek.SUNDAY

    private const val DAYS_TO_SCAN = 8
}
