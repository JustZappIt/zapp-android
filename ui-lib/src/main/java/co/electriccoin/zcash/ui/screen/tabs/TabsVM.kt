package co.electriccoin.zcash.ui.screen.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.P2pRail
import co.electriccoin.zcash.ui.common.pricing.usecase.PrewarmPortfolioHistoryUseCase
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.provider.PreferredP2pPaymentMethodProvider
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepository
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPeerActiveOrdersUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectFiatCurrencyUseCase
import co.electriccoin.zcash.ui.screen.chat.ChatContactsArgs
import co.electriccoin.zcash.ui.screen.chat.ChatProfileArgs
import co.electriccoin.zcash.ui.screen.chat.ChatSettingsArgs
import co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerArgs
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedArgs
import co.electriccoin.zcash.ui.screen.securitysettings.SecuritySettingsArgs
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pPaymentMethodArgs
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pTransactionsArgs
import co.electriccoin.zcash.ui.screen.settings.portfoliochart.PortfolioChartSettingsArgs
import co.electriccoin.zcash.ui.screen.tor.settings.TorSettingsArgs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.peer.PeerOrderSnapshot

@Suppress("TooManyFunctions")
class TabsVM(
    private val navigationRouter: NavigationRouter,
    private val isExchangeRateEnabledStorageProvider: IsExchangeRateEnabledStorageProvider,
    private val preferredFiatProvider: PreferredFiatProvider,
    preferredP2pPaymentMethodProvider: PreferredP2pPaymentMethodProvider,
    private val navigateToSelectFiatCurrency: NavigateToSelectFiatCurrencyUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val prewarmPortfolioHistory: PrewarmPortfolioHistoryUseCase,
    private val getPeerActiveOrders: GetPeerActiveOrdersUseCase,
    private val peerCashOutRepository: PeerCashOutRepository,
) : ViewModel() {
    init {
        // Chats is the initial tab, so fill the small default price window in parallel with
        // normal chat use. This does not wait for wallet transactions or trigger an All backfill.
        viewModelScope.launch { prewarmPortfolioHistory() }
    }

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
                initialValue = P2pRail.DEFAULT
            )

    /**
     * Whether anything is still on offer. A cash-out can wait hours, so the row that leads to the
     * activity list says so rather than looking like plain settings.
     *
     * Merged from two sources. An order that is bridging, approving, or broadcast but not yet
     * indexed exists on no indexer, and reading only the chain shows nothing at exactly the moment
     * the user goes looking. Re-read whenever an attempt settles, which is when the chain gains one.
     */
    private val peerChainOrders =
        peerCashOutRepository
            .runs
            .map { runs -> runs.count { it.holdsFunds } }
            .distinctUntilChanged()
            .map { getPeerActiveOrders() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = emptyList<PeerOrderSnapshot>()
            )

    internal val hasPeerActivity =
        combine(peerCashOutRepository.runs, peerChainOrders) { runs, chain ->
            // A failed attempt is never evicted, so counting it here would leave the row claiming a
            // cash-out is in progress for the rest of the session.
            runs.any { it.isUnindexed && it.failure == null } || chain.isNotEmpty()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = false
        )

    fun onRestoreWalletClick() = navigationRouter.forward(RestoreSeedArgs)

    fun onChatProfileClick() = navigationRouter.forward(ChatProfileArgs)

    fun onContactsClick() = navigationRouter.forward(ChatContactsArgs)

    fun onAppLockClick() = navigationRouter.forward(SecuritySettingsArgs)

    fun onChooseServerClick() = navigationRouter.forward(ChooseServerArgs)

    fun onTorClick() = navigationRouter.forward(TorSettingsArgs)

    fun onChatSettingsClick() = navigationRouter.forward(ChatSettingsArgs)

    fun onCopyPublicKeyClick(publicKey: String) = copyToClipboard(publicKey, isSensitive = false)

    fun onP2pPaymentMethodClick() = navigationRouter.forward(P2pPaymentMethodArgs)

    fun onBaseAccountClick() = navigationRouter.forward(P2pTransactionsArgs)

    fun onPortfolioChartClick() = navigationRouter.forward(PortfolioChartSettingsArgs)

    fun onLocalCurrencyClick() =
        viewModelScope.launch {
            navigateToSelectFiatCurrency(localCurrency.value)?.let {
                preferredFiatProvider.store(it)
                isExchangeRateEnabledStorageProvider.store(true)
                prewarmPortfolioHistory()
            }
        }
}
