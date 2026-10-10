package co.electriccoin.zcash.ui.common.invest

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.demo.DemoAccountDataSource
import co.electriccoin.zcash.ui.common.invest.demo.DemoWallet
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DemoAccountDataSourceTest {
    @Test
    fun `the phone's account shows the demo balance, all shielded and spendable, and follows it`() =
        runTest {
            val wallet = DemoWallet()
            val source = DemoAccountDataSource(real(ZASHI), wallet, backgroundScope)

            val account = source.getSelectedAccount()
            assertEquals(DemoWallet.START, account.totalBalance)
            assertEquals(DemoWallet.START, account.spendableShieldedBalance)
            assertEquals(Zatoshi(0), account.totalTransparentBalance)

            assertTrue(wallet.trySpend(BigDecimal("2.0001")))
            assertEquals(Zatoshi(699_990_000L), source.selectedAccount.first()!!.spendableShieldedBalance)
            wallet.receive(BigDecimal("1.5"))
            assertEquals(Zatoshi(849_990_000L), source.getZashiAccount().totalBalance)
        }

    @Test
    fun `a Keystone account passes through untouched`() =
        runTest {
            val keystone = mockk<KeystoneAccount>()
            val source = DemoAccountDataSource(real(keystone), DemoWallet(), backgroundScope)

            assertSame(keystone, source.getSelectedAccount())
        }

    private fun real(selected: co.electriccoin.zcash.ui.common.model.WalletAccount): AccountDataSource =
        mockk<AccountDataSource>().also {
            every { it.allAccounts } returns MutableStateFlow(listOf(selected))
            every { it.selectedAccount } returns MutableStateFlow(selected)
            every { it.zashiAccount } returns MutableStateFlow(ZASHI)
            coEvery { it.getSelectedAccount() } returns selected
            coEvery { it.getZashiAccount() } returns ZASHI
        }

    private companion object {
        val REAL_BALANCE = WalletBalance(Zatoshi(12_345L), Zatoshi(100L), Zatoshi(200L))
        val ZASHI =
            ZashiAccount(
                sdkAccount = mockk(),
                unified = UnifiedInfo(mockk(), REAL_BALANCE),
                sapling = SaplingInfo(mockk(), REAL_BALANCE),
                ironwoodBalance = REAL_BALANCE,
                transparent = TransparentInfo(mockk(), Zatoshi(5_000L)),
                isSelected = true,
            )
    }
}
