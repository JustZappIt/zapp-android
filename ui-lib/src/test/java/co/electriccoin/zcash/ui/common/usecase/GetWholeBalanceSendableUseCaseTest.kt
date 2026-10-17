package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.shortfall
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GetWholeBalanceSendableUseCaseTest {
    @Test
    fun `the whole balance less the fee the wallet names is what drains it`() =
        runTest {
            val fixture = fixture(spendable = 1_000_000L)
            fixture.short(amount = 1_000_000L, by = 15_000L)

            assertEquals(Zatoshi(985_000L), fixture.useCase())
            coVerify { fixture.proposals.createProposal(fixture.account, match { it.amount == Zatoshi(985_000L) }) }
        }

    @Test
    fun `a balance that moved in between is fitted again`() =
        runTest {
            val fixture = fixture(spendable = 1_000_000L)
            fixture.short(amount = 1_000_000L, by = 15_000L)
            fixture.short(amount = 985_000L, by = 5_000L)

            assertEquals(Zatoshi(980_000L), fixture.useCase())
        }

    @Test
    fun `a shortfall the wallet doesn't name is passed on`() =
        runTest {
            val fixture = fixture(spendable = 1_000_000L)
            coEvery { fixture.proposals.createProposal(any(), any()) } throws InsufficientFundsException()

            assertFailsWith<InsufficientFundsException> { fixture.useCase() }
        }

    @Test
    fun `a fee as large as the balance leaves nothing to send`() =
        runTest {
            val fixture = fixture(spendable = 10_000L)
            fixture.short(amount = 10_000L, by = 10_000L)

            assertFailsWith<InsufficientFundsException> { fixture.useCase() }
        }

    @Test
    fun `the fee is priced to the recipient asked for`() =
        runTest {
            val fixture = fixture(spendable = 1_000_000L)
            val recipient = WalletAddress.Unified.new(RECIPIENT)

            fixture.useCase { recipient }

            coVerify { fixture.proposals.createProposal(any(), match { it.destination == recipient }) }
        }

    @Test
    fun `the shortfall is read from librustzcash's message`() {
        fun failure(message: String) =
            TransactionEncoderException.ProposalFromParametersException(IllegalStateException(message))

        assertEquals(
            Zatoshi(15_000L),
            failure("Insufficient balance (have 1000000, need 1015000 including fee)").shortfall()
        )
        assertNull(failure("The transaction requires an additional change output of 5000 zatoshis").shortfall())
    }

    private suspend fun fixture(spendable: Long): Fixture {
        val unified = WalletAddress.Unified.new(UNIFIED)
        val account =
            mockk<WalletAccount> {
                every { spendableShieldedBalance } returns Zatoshi(spendable)
                every { this@mockk.unified } returns UnifiedInfo(unified, mockk(relaxed = true))
            }
        val accounts = mockk<AccountDataSource> { coEvery { getSelectedAccount() } returns account }
        val proposals =
            mockk<ProposalDataSource> {
                coEvery { createProposal(any(), any()) } returns mockk<RegularTransactionProposal>()
            }
        return Fixture(account, proposals, GetWholeBalanceSendableUseCase(accounts, proposals))
    }

    private class Fixture(
        val account: WalletAccount,
        val proposals: ProposalDataSource,
        val useCase: GetWholeBalanceSendableUseCase,
    ) {
        /** Proposing [amount] fails [by] zatoshi short, as the wallet reports it. */
        fun short(amount: Long, by: Long) {
            coEvery {
                proposals.createProposal(account, match { it.amount == Zatoshi(amount) })
            } throws InsufficientFundsException(Zatoshi(by))
        }
    }

    private companion object {
        const val UNIFIED = "u1ownaddressforunittests"
        const val RECIPIENT = "u1recipientaddressforunittests"
    }
}
