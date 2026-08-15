package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.P2pProvider
import co.electriccoin.zcash.ui.common.model.P2pRail
import co.electriccoin.zcash.ui.common.provider.PreferredP2pPaymentMethodProvider
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOfframpBaseAddressUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.peer.PeerConfigProvider
import xyz.justzappit.offramp.peer.PeerPlatform

internal class P2pPaymentMethodVM(
    private val copyToClipboard: CopyToClipboardUseCase,
    private val getOfframpBaseAddress: GetOfframpBaseAddressUseCase,
    private val preferredP2pPaymentMethodProvider: PreferredP2pPaymentMethodProvider,
    private val peerConfigProvider: PeerConfigProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val baseAddress = MutableStateFlow<String?>(null)
    private val isAddressCopied = MutableStateFlow(false)
    private val selectedRail = MutableStateFlow<P2pRail?>(null)
    private val isSaveInProgress = MutableStateFlow(false)
    private var copyResetJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { getOfframpBaseAddress() }
                .onSuccess { addr -> baseAddress.update { addr } }
                .onFailure { Twig.warn(it) { "P2pPaymentMethodVM: base address resolve failed" } }
        }
    }

    val state: StateFlow<P2pPaymentMethodState> =
        combine(
            baseAddress,
            isAddressCopied,
            preferredP2pPaymentMethodProvider.observe(),
            selectedRail,
            isSaveInProgress,
        ) { addr, copied, savedRail, selected, saving ->
            createState(
                baseAddress = addr,
                isAddressCopied = copied,
                savedRail = savedRail,
                selectedRail = selected,
                isSaveInProgress = saving,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    baseAddress = null,
                    isAddressCopied = false,
                    savedRail = P2pRail.DEFAULT,
                    selectedRail = null,
                    isSaveInProgress = false,
                ),
        )

    private fun createState(
        baseAddress: String?,
        isAddressCopied: Boolean,
        savedRail: P2pRail,
        selectedRail: P2pRail?,
        isSaveInProgress: Boolean,
    ): P2pPaymentMethodState {
        val effectiveSelection = selectedRail ?: savedRail
        return P2pPaymentMethodState(
            baseAddress = baseAddress,
            isAddressCopied = isAddressCopied,
            sections = buildSections(effectiveSelection),
            saveButton =
                ButtonState(
                    text = stringRes(R.string.settings_p2p_payment_method_save),
                    isEnabled = effectiveSelection != savedRail && !isSaveInProgress,
                    isLoading = isSaveInProgress,
                    onClick = ::onSaveClick,
                ),
            onCopyBaseAddress = ::onCopyBaseAddress,
            onBack = ::onBack,
        )
    }

    // Peer only exists on Base mainnet, so on any other build the section is absent rather than
    // present and failing at the first call.
    private fun buildSections(selection: P2pRail): List<P2pPaymentMethodSectionState> =
        buildList {
            add(
                P2pPaymentMethodSectionState(
                    provider = P2pProvider.P2P_ME,
                    items =
                        P2pPaymentMethod.entries.map { method ->
                            itemFor(P2pRail.ScanAndPay(method.currency), method.available, selection)
                        },
                ),
            )
            if (peerConfigProvider.isAvailable) {
                add(
                    P2pPaymentMethodSectionState(
                        provider = P2pProvider.PEER,
                        items =
                            PeerPlatform.entries.map { platform ->
                                itemFor(P2pRail.PeerCashOut(platform), isAvailable = true, selection = selection)
                            },
                    ),
                )
            }
        }

    private fun itemFor(
        rail: P2pRail,
        isAvailable: Boolean,
        selection: P2pRail,
    ): P2pPaymentMethodItemState =
        P2pPaymentMethodItemState(
            rail = rail,
            isSelected = rail == selection,
            isAvailable = isAvailable,
            onClick = { onRailClick(rail, isAvailable) },
        )

    private fun onRailClick(rail: P2pRail, isAvailable: Boolean) {
        if (isAvailable) {
            selectedRail.update { rail }
        }
    }

    private fun onSaveClick() {
        if (isSaveInProgress.value) return
        viewModelScope.launch {
            val selection = selectedRail.value ?: return@launch
            val savedSelection = preferredP2pPaymentMethodProvider.get()
            if (selection == savedSelection) {
                selectedRail.update { null }
                return@launch
            }

            isSaveInProgress.update { true }
            try {
                preferredP2pPaymentMethodProvider.store(selection)
                selectedRail.update { null }
                navigationRouter.back()
            } finally {
                isSaveInProgress.update { false }
            }
        }
    }

    private fun onBack() {
        if (!isSaveInProgress.value) {
            navigationRouter.back()
        }
    }

    private fun onCopyBaseAddress() {
        val addr = baseAddress.value ?: return
        copyToClipboard(addr)
        isAddressCopied.update { true }
        copyResetJob?.cancel()
        copyResetJob =
            viewModelScope.launch {
                delay(COPY_FEEDBACK_MS)
                isAddressCopied.update { false }
            }
    }

    override fun onCleared() {
        super.onCleared()
        copyResetJob?.cancel()
    }

    private companion object {
        const val COPY_FEEDBACK_MS = 2_000L
    }
}
