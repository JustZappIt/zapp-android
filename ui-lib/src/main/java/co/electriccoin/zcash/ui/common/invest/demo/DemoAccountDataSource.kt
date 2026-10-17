package co.electriccoin.zcash.ui.common.invest.demo

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The demo build's accounts: the phone's own account shows [DemoWallet]'s balance, all of it shielded and
 * spendable, in place of what the wallet really holds. Keystone accounts and everything else pass through. Only
 * the balance is pretended, so a real send of demo ZEC fails when the wallet builds it.
 */
internal class DemoAccountDataSource(
    private val real: AccountDataSource,
    private val wallet: DemoWallet,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AccountDataSource by real {
    override val allAccounts: StateFlow<List<WalletAccount>?> =
        combine(real.allAccounts, wallet.balance) { accounts, zats -> accounts?.map { it.withDemoBalance(zats) } }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = real.allAccounts.value?.map { it.withDemoBalance(wallet.balance.value) },
            )

    override val selectedAccount: Flow<WalletAccount?> =
        combine(real.selectedAccount, wallet.balance) { account, zats -> account?.withDemoBalance(zats) }

    override val zashiAccount: Flow<ZashiAccount?> =
        combine(real.zashiAccount, wallet.balance) { account, zats -> account?.demo(zats) }

    override suspend fun getAllAccounts(): List<WalletAccount> =
        real.getAllAccounts().map { it.withDemoBalance(wallet.balance.value) }

    override suspend fun getSelectedAccount(): WalletAccount =
        real.getSelectedAccount().withDemoBalance(wallet.balance.value)

    override suspend fun getZashiAccount(): ZashiAccount = real.getZashiAccount().demo(wallet.balance.value)

    private fun WalletAccount.withDemoBalance(zats: Zatoshi): WalletAccount =
        if (this is ZashiAccount) demo(zats) else this

    private fun ZashiAccount.demo(zats: Zatoshi): ZashiAccount =
        copy(
            unified = unified.copy(balance = spendable(zats)),
            sapling = sapling.copy(balance = spendable(ZERO)),
            ironwoodBalance = spendable(ZERO),
            transparent = transparent.copy(balance = ZERO),
        )

    private fun spendable(zats: Zatoshi) = WalletBalance(available = zats, changePending = ZERO, valuePending = ZERO)

    private companion object {
        val ZERO = Zatoshi(0)
    }
}
