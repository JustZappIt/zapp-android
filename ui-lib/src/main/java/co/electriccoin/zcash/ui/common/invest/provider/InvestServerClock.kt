package co.electriccoin.zcash.ui.common.invest.provider

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The phone's clock corrected by server `Date` headers, 1Click's winning over the NEAR RPC hosts'. Logins are
 * signed with it, and everything compared with a time 1Click set reads it.
 */
class InvestServerClock(
    private val deviceNowMillis: () -> Long = System::currentTimeMillis,
) : Clock {
    private val offsetMillis = AtomicReference<Long?>(null)

    @Volatile
    private var hasOneClickTime = false

    fun nowMillis(): Long = deviceNowMillis() + (offsetMillis.get() ?: 0L)

    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis())

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
