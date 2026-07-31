package co.electriccoin.zcash.ui.screen.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.provider.PreferredP2pPaymentMethodProvider
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectFiatCurrencyUseCase
import co.electriccoin.zcash.ui.screen.chat.ChatContactsArgs
import co.electriccoin.zcash.ui.screen.chat.ChatProfileArgs
import co.electriccoin.zcash.ui.screen.chat.backgrounddelivery.BackgroundDeliverySettingsArgs
import co.electriccoin.zcash.ui.screen.chat.onlinestatus.OnlineStatusSettingsArgs
import co.electriccoin.zcash.ui.screen.chat.readreceipts.ReadReceiptsSettingsArgs
import co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerArgs
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedArgs
import co.electriccoin.zcash.ui.screen.securitysettings.SecuritySettingsArgs
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pPaymentMethodArgs
import co.electriccoin.zcash.ui.screen.tor.settings.TorSettingsArgs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.p2p.CurrencyCode

class TabsVM(
    private val navigationRouter: NavigationRouter,
    private val isExchangeRateEnabledStorageProvider: IsExchangeRateEnabledStorageProvider,
    private val preferredFiatProvider: PreferredFiatProvider,
    preferredP2pPaymentMethodProvider: PreferredP2pPaymentMethodProvider,
    private val navigateToSelectFiatCurrency: NavigateToSelectFiatCurrencyUseCase,
) : ViewModel() {
    val localCurrency =
        preferredFiatProvider
            .observe()
            .map { it ?: FiatCurrency.USD }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = FiatCurrency.USD
            )

    val p2pPaymentMethod =
        preferredP2pPaymentMethodProvider
            .observe()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = CurrencyCode.Inr
            )

    fun onRestoreWalletClick() = navigationRouter.forward(RestoreSeedArgs)

    fun onChatProfileClick() = navigationRouter.forward(ChatProfileArgs)

    fun onContactsClick() = navigationRouter.forward(ChatContactsArgs)

    fun onAppLockClick() = navigationRouter.forward(SecuritySettingsArgs)

    fun onChooseServerClick() = navigationRouter.forward(ChooseServerArgs)

    fun onTorClick() = navigationRouter.forward(TorSettingsArgs)

    fun onBackgroundDeliveryClick() = navigationRouter.forward(BackgroundDeliverySettingsArgs)

    fun onReadReceiptsClick() = navigationRouter.forward(ReadReceiptsSettingsArgs)

    fun onOnlineStatusClick() = navigationRouter.forward(OnlineStatusSettingsArgs)

    fun onP2pPaymentMethodClick() = navigationRouter.forward(P2pPaymentMethodArgs)

    fun onLocalCurrencyClick() =
        viewModelScope.launch {
            navigateToSelectFiatCurrency(localCurrency.value)?.let {
                preferredFiatProvider.store(it)
                isExchangeRateEnabledStorageProvider.store(true)
            }
        }
}
