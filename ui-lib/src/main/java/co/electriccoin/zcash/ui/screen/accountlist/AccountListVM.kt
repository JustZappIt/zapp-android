package co.electriccoin.zcash.ui.screen.accountlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.SelectWalletAccountUseCase
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.collections.map

class AccountListVM(
    getWalletAccounts: GetWalletAccountsUseCase,
    private val selectWalletAccount: SelectWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state =
        getWalletAccounts
            .observe()
            .map { accounts ->
                val items =
                    accounts
                        .orEmpty()
                        .map<WalletAccount, AccountListItem> { account ->
                            AccountListItem.Account(
                                ZashiAccountListItemState(
                                    title = account.name,
                                    subtitle = stringResByAddress(account.unified.address.address),
                                    icon =
                                        when (account) {
                                            is KeystoneAccount -> R.drawable.ic_item_keystone
                                            is ZashiAccount -> R.drawable.ic_item_zashi
                                        },
                                    isSelected = account.isSelected,
                                    onClick = { onAccountClicked(account) }
                                )
                            )
                        }

                AccountListState(
                    items = items,
                    isLoading = accounts == null,
                    onBack = ::onBack,
                    addWalletButton = null
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Companion.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private fun onAccountClicked(account: WalletAccount) =
        viewModelScope.launch {
            selectWalletAccount(account)
        }

    private fun onBack() = navigationRouter.back()
}
