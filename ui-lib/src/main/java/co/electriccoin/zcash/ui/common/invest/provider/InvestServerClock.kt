package co.electriccoin.zcash.ui.common.invest.provider

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The time Invest signs with. `/v0/auth/authenticate` rejects a login whose nonce start time is more than
 * about four minutes from its own clock (measured 2026-09-26), so a phone whose clock has drifted would
 * never sign in. Response `Date` headers set the offset: 1Click's, whose clock is the one that judges the
 * login, win over the NEAR RPC hosts', which only stand in until 1Click has answered once. Until any has
 * been seen, the device clock is used as is.
 *
 * Everything Invest compares with a time 1Click set (a price hold, an intent deadline) reads this clock, the
 * screens' countdowns included: on a phone whose clock is off, a mix of the two clocks would open every review
 * sheet already expired, or count down past a hold the engine has already ended.
 */
class InvestServerClock(
    private val deviceNowMillis: () -> Long = System::currentTimeMillis,
) : Clock {
    private val offsetMillis = AtomicReference<Long?>(null)

    @Volatile
    private var hasOneClickTime = false

    fun nowMillis(): Long = deviceNowMillis() + (offsetMillis.get() ?: 0L)

    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis())

    /** Whether a server `Date` has been seen, i.e. whether [nowMillis] is corrected at all. */
    val isSynchronised: Boolean get() = offsetMillis.get() != null

    /** Records an RFC 1123 `Date` header value; malformed values are ignored. */
    fun observe(
        dateHeader: String?,
        fromOneClick: Boolean,
    ) {
        if (!fromOneClick && hasOneClickTime) return
        val serverMillis =
            dateHeader
                ?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull() }
                ?.toInstant()
                ?.toEpochMilli()
                ?: return
        offsetMillis.set(serverMillis - deviceNowMillis())
        if (fromOneClick) hasOneClickTime = true
    }
}
