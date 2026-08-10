package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProposalDataSourceSlipstreamTest {
    @Test
    fun submitTransactionSupportsSynchronizerImplementationsOtherThanSdkSynchronizer() =
        runTest {
            val synchronizer = mockk<Synchronizer>()
            val synchronizerProvider = mockk<SynchronizerProvider>()
            val proposal = mockk<Proposal>()
            val spendingKey = mockk<UnifiedSpendingKey>()
            val transactionId = FirstClassByteArray(byteArrayOf(1))

            coEvery { synchronizerProvider.getSynchronizer() } returns synchronizer
            coEvery {
                synchronizer.createProposedTransactions(
                    proposal = proposal,
                    usk = spendingKey,
                )
            } returns flowOf(TransactionSubmitResult.Success(transactionId))

            val result =
                ProposalDataSourceImpl(synchronizerProvider).submitTransaction(
                    proposal = proposal,
                    usk = spendingKey,
                )

            assertEquals(SubmitResult.Success(txIds = listOf("01")), result)
            coVerify(exactly = 1) {
                synchronizer.createProposedTransactions(
                    proposal = proposal,
                    usk = spendingKey,
                )
            }
        }
}
