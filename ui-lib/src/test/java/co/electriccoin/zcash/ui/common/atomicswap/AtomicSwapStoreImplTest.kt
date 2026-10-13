// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.provider.InMemoryPreferenceProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.SwapDeposit
import xyz.justzappit.offramp.atomicswap.ZcashTransaction
import xyz.justzappit.offramp.atomicswap.ZcashTxId
import kotlin.test.Test
import kotlin.test.assertEquals

class AtomicSwapStoreImplTest {
    @Test
    fun `kept swaps read typed and are written back unchanged`() =
        runTest {
            val preferences = InMemoryPreferenceProvider()
            preferences.putString(PreferenceKey(KEY), KEPT)
            val store = AtomicSwapStoreImpl(preferences.encrypted())

            val history = store.observeHistory.first()
            store.save(checkNotNull(store.active()))

            assertEquals(listOf(1, 2), history.map { it.index })
            assertEquals(
                SwapDeposit.Kept(ZcashTransaction(ZcashTxId.parse(TX), "0400", 4_200_040)),
                history.first().deposit,
            )
            assertEquals(AtomicSwapOutcome.Paid, history.first().outcome)
            assertEquals(TxHash.fromHex(PAYOUT), history.first().payoutTx)
            assertEquals(SwapDeposit.Started, history.last().deposit)
            assertEquals(KEPT, preferences.getString(PreferenceKey(KEY)))
        }

    @Test
    fun `a paid swap's payout found later is kept in place`() =
        runTest {
            val preferences = InMemoryPreferenceProvider()
            preferences.putString(PreferenceKey(KEY), KEPT)
            val store = AtomicSwapStoreImpl(preferences.encrypted())

            store.update(1) { it.copy(payoutTx = TxHash.fromHex("0x" + "b1".repeat(32))) }

            assertEquals(KEPT.replace(PAYOUT, "0x" + "b1".repeat(32)), preferences.getString(PreferenceKey(KEY)))
        }

    private companion object {
        const val KEY = "atomicswap_state_v3"
        val TX = "ab".repeat(32)
        val PAYOUT = "0x" + "a1".repeat(32)
        private val QUOTE =
            """{"quoteId":"0x${"22".repeat(32)}","maker":"0x2bac02b5032e9092493814c705f156b49e288922",""" +
                """"makerShare":"0x${"0a".repeat(64)}","makerProof":"0x${"06".repeat(64)}","chainId":11155111,""" +
                """"contract":"0xbd9a37f47a988aefc4d80395727f41feb698e225",""" +
                """"token":"0x5764d0044bef5aa839e0ddafe2073421101b9ed8","amount":"1000000","depositZat":202021,""" +
                """"expiresAt":1790000300}"""

        /** A swap paid out, and one depositing. */
        val KEPT =
            """{"nextIndex":3,"active":{"index":2,"quote":$QUOTE,"swapId":"0x${"5d".repeat(32)}",""" +
                """"zcashHeight":4200100,"acceptedAt":1790001000,"receives":"977550","relayerFee":"20000",""" +
                """"depositAttempted":true,"maxTotalZat":212021},""" +
                """"history":[{"index":1,"quote":$QUOTE,"swapId":"0x${"5c".repeat(32)}","zcashHeight":4200000,""" +
                """"acceptedAt":1790000000,"receives":"977550","relayerFee":"20000","depositAttempted":true,""" +
                """"outcome":{"type":"paid"},"finishedAt":1790003000,"payoutTx":"$PAYOUT",""" +
                """"deposit":{"txId":"$TX","raw":"0400","expiryHeight":4200040}},""" +
                """{"index":2,"quote":$QUOTE,"swapId":"0x${"5d".repeat(32)}","zcashHeight":4200100,""" +
                """"acceptedAt":1790001000,"receives":"977550","relayerFee":"20000","depositAttempted":true,""" +
                """"maxTotalZat":212021}]}"""
    }
}
