package co.electriccoin.zcash.crash

import kotlin.time.Instant

data class ReportableException(
    val exceptionClass: String,
    val exceptionTrace: String,
    val appVersion: String,
    val isUncaught: Boolean,
    val time: Instant
) {
    companion object
}
