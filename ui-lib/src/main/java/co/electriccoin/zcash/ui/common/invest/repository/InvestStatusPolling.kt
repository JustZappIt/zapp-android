package co.electriccoin.zcash.ui.common.invest.repository

import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_GONE
import java.net.HttpURLConnection.HTTP_NOT_FOUND

/** How both Invest engines poll 1Click's status for a trade. */
internal object InvestStatusPolling {
    const val INTERVAL_MS = 5_000L

    /** Consecutive [PERMANENT_ERRORS] after which a trade needs attention. */
    const val MAX_REJECTED = 3
    val PERMANENT_ERRORS = setOf(HTTP_BAD_REQUEST, HTTP_NOT_FOUND, HTTP_GONE)
}
