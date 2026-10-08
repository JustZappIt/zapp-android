// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.BaseBalance
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins when a money flow may show "Add funds" instead of its form: only for a wallet that has
 * synced and holds nothing, never while a balance could still be on its way.
 */
class ObserveFundingUseCaseTest {
    @Test
    fun `a synced wallet holding nothing is empty`() {
        assertEquals(Funding.EMPTY, next(total = 0L, status = Synchronizer.Status.SYNCED))
    }

    @Test
    fun `any balance is funded, whatever the sync state`() {
        assertEquals(Funding.FUNDED, next(total = 1L, status = Synchronizer.Status.SYNCING))
        assertEquals(Funding.FUNDED, next(total = 1L, status = Synchronizer.Status.SYNCED))
    }

    @Test
    fun `an unsynced wallet is not called empty`() {
        assertEquals(Funding.UNKNOWN, next(total = 0L, status = Synchronizer.Status.SYNCING))
        assertEquals(Funding.UNKNOWN, next(total = 0L, status = Synchronizer.Status.INITIALIZING))
        assertEquals(Funding.UNKNOWN, next(total = 0L, status = null))
    }

    @Test
    fun `a restoring wallet is not called empty even when its status reads synced`() {
        assertEquals(
            Funding.UNKNOWN,
            next(total = 0L, status = Synchronizer.Status.SYNCED, isRestoring = true),
        )
    }

    @Test
    fun `no account yet is unknown`() {
        assertEquals(
            Funding.UNKNOWN,
            nextZecFunding(Funding.UNKNOWN, total = null, status = Synchronizer.Status.SYNCED, isRestoring = false),
        )
    }

    @Test
    fun `empty holds through the catch-up sync each new block starts`() {
        assertEquals(
            Funding.EMPTY,
            next(previous = Funding.EMPTY, total = 0L, status = Synchronizer.Status.SYNCING),
        )
    }

    @Test
    fun `empty gives way as soon as funds arrive`() {
        assertEquals(
            Funding.FUNDED,
            next(previous = Funding.EMPTY, total = 5_000L, status = Synchronizer.Status.SYNCING),
        )
    }

    @Test
    fun `USDC on Base funds a flow that can pay from it`() {
        assertEquals(Funding.FUNDED, withBaseUsdc(Funding.EMPTY, BaseBalance.Loaded(Usdc6.ofMicros(1L))))
    }

    @Test
    fun `empty ZEC and zero USDC is empty`() {
        assertEquals(Funding.EMPTY, withBaseUsdc(Funding.EMPTY, BaseBalance.Loaded(Usdc6.ZERO)))
    }

    @Test
    fun `an unread Base balance is never called empty`() {
        assertEquals(Funding.UNKNOWN, withBaseUsdc(Funding.EMPTY, BaseBalance.Loading))
        assertEquals(Funding.UNKNOWN, withBaseUsdc(Funding.EMPTY, BaseBalance.Unavailable))
    }

    @Test
    fun `ZEC alone funds the flow whatever Base says`() {
        assertEquals(Funding.FUNDED, withBaseUsdc(Funding.FUNDED, BaseBalance.Unavailable))
    }

    private fun next(
        total: Long,
        status: Synchronizer.Status?,
        previous: Funding = Funding.UNKNOWN,
        isRestoring: Boolean = false,
    ) = nextZecFunding(previous, Zatoshi(total), status, isRestoring)
}
