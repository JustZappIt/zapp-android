// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.coroutines.flow.Flow
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6

interface OnrampDriver {
    /**
     * Bounds and kill switch for [currency]. The corridor travels with the request because the
     * service serves them all and derives each one's caps from its own live buy price.
     */
    suspend fun limits(currency: CurrencyCode): OnrampLimits

    /**
     * The corridors the service will actually sell ZEC in.
     *
     * Buying and paying are separate markets with separate merchants, so this is not the set a
     * Scan & Pay picker offers: Bolivia pays at any size but buys only 1 USDC, Peru pays at every
     * size but stops buying at 20. Asking the service rather than mirroring its list here keeps a
     * corridor's arrival or withdrawal from needing an app release.
     *
     * Empty when the service cannot be reached — "no idea", so callers fall back to the default
     * corridor rather than trusting a stale preference.
     */
    suspend fun buyCorridors(): Set<CurrencyCode>

    /** The address that signs requests, and therefore the only address USDC may settle to. */
    suspend fun recipientAddress(): Address

    /**
     * Single-use price lock, roughly 90 seconds. Re-quote rather than re-pricing silently.
     * [currency] is the corridor the user chose, so it travels with the request rather than being
     * fixed when the driver is built.
     */
    suspend fun quote(
        fiatAmount: Usdc6,
        currency: CurrencyCode,
    ): OnrampQuote

    fun start(quote: OnrampQuote): Flow<OnrampStatus>

    /** Only from [OnrampPhase.AWAITING_PAYMENT], and only once the user confirms they really paid. */
    fun confirmPaid(checkpoint: OnrampCheckpoint): Flow<OnrampStatus>

    fun resume(checkpoint: OnrampCheckpoint): Flow<OnrampStatus>

    fun cancel(checkpoint: OnrampCheckpoint): Flow<OnrampStatus>
}
