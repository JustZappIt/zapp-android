// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import xyz.justzappit.offramp.p2p.CurrencyCode

/**
 * What the widget hands back on the redirect: a one-time `code` on success, an `error` otherwise,
 * and our own `state` either way.
 *
 * The `redirect_uri` is exact-matched against the tenant's allowlist, so nothing of ours can
 * travel in it. The corridor rides in `state` instead, beside a nonce, which is what lets a
 * cold-started process rebuild the route the code belongs to.
 */
data class LivenessReturn(
    val code: String?,
    val error: String?,
    val state: String?,
) {
    val currency: CurrencyCode? get() = state?.let(::currencyFromState)

    companion object {
        const val CODE_QUERY = "code"
        const val ERROR_QUERY = "error"
        const val STATE_QUERY = "state"

        private const val STATE_SEPARATOR = '.'

        fun state(nonce: String, currency: CurrencyCode): String = nonce + STATE_SEPARATOR + currency.code

        fun currencyFromState(state: String): CurrencyCode? =
            CurrencyCode.fromCodeOrNull(state.substringAfterLast(STATE_SEPARATOR, missingDelimiterValue = ""))
    }
}
