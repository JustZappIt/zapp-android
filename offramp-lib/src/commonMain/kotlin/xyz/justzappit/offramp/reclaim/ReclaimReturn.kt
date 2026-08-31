// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reclaim

import io.ktor.http.encodeURLParameter
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.SocialPlatform

/** Non-secret state needed to continue a Reclaim session after Android recreates the process. */
object ReclaimReturn {
    const val SESSION_ID_QUERY = "sessionId"
    const val PLATFORM_QUERY = "socialPlatform"
    const val CURRENCY_QUERY = "currency"

    fun url(
        baseUrl: String,
        sessionId: String,
        platform: SocialPlatform,
        currency: CurrencyCode,
    ): String {
        val separator = if ('?' in baseUrl) '&' else '?'
        return buildString {
            append(baseUrl)
            append(separator)
            append(SESSION_ID_QUERY)
            append('=')
            append(sessionId.encodeURLParameter())
            append('&')
            append(PLATFORM_QUERY)
            append('=')
            append(platform.name.encodeURLParameter())
            append('&')
            append(CURRENCY_QUERY)
            append('=')
            append(currency.code.encodeURLParameter())
        }
    }
}
