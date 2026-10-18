// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.privateusd.offer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.atomicswap.AtomicSwapKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class AtomicSwapZcashInfoTest {
    private val wallet = mockk<Synchronizer>()
    private val keys = mockk<AtomicSwapKeys> { coEvery { depositAddress(any(), any()) } returns DEPOSIT_ADDRESS }
    private val info =
        AtomicSwapZcashInfo(
            synchronizerProvider = mockk<SynchronizerProvider> { coEvery { getSynchronizer() } returns wallet },
            accountDataSource = mockk<AccountDataSource>(relaxed = true),
            transactions = mockk(),
            keys = keys,
        )
    private val offer = offer(index = 1, requested = 1_000_000, depositZat = DEPOSIT_ZAT, expiresAt = 0)

    @Test
    fun `a deposit the balance can't cover with its fee is short by what the wallet says it needs`() =
        runTest {
            proposalFails(
                "Error creating transaction proposal: Insufficient balance (have 955821, need 960821 including fee)",
            )

            assertEquals(DepositFee.Short(5_000), info.depositFee(offer))
        }

    @Test
    fun `a proposal that fails for another reason is a wallet that isn't ready`() =
        runTest {
            proposalFails("Error creating transaction proposal: Must scan blocks first")

            assertEquals(DepositFee.Unready, info.depositFee(offer))
        }

    @Test
    fun `a deposit that takes two transactions is split, whatever its fee`() =
        runTest {
            val proposal =
                mockk<Proposal> {
                    every { transactionCount() } returns 2
                    every { totalFeeRequired() } returns Zatoshi(20_000)
                }
            coEvery { wallet.proposeTransfer(any(), DEPOSIT_ADDRESS, Zatoshi(DEPOSIT_ZAT), any()) } returns
                proposal

            assertEquals(DepositFee.Split, info.depositFee(offer))
        }

    private fun proposalFails(message: String) {
        coEvery { wallet.proposeTransfer(any(), DEPOSIT_ADDRESS, Zatoshi(DEPOSIT_ZAT), any()) } throws
            TransactionEncoderException.ProposalFromParametersException(RuntimeException(message))
    }

    private companion object {
        const val DEPOSIT_ADDRESS = "u1deposit"
        const val DEPOSIT_ZAT = 945_821L
    }
}
