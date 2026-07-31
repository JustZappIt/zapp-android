package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
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
import xyz.justzappit.offramp.p2p.CurrencyCode

internal class P2pPaymentMethodVM(
    private val copyToClipboard: CopyToClipboardUseCase,
    private val getOfframpBaseAddress: GetOfframpBaseAddressUseCase,
    private val preferredP2pPaymentMethodProvider: PreferredP2pPaymentMethodProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val baseAddress = MutableStateFlow<String?>(null)
    private val isAddressCopied = MutableStateFlow(false)
    private val selectedPaymentMethod = MutableStateFlow<CurrencyCode?>(null)
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
            selectedPaymentMethod,
            isSaveInProgress,
        ) { addr, copied, savedPaymentMethod, selectedPaymentMethod, isSaveInProgress ->
            createState(
                baseAddress = addr,
                isAddressCopied = copied,
                savedPaymentMethod = savedPaymentMethod,
                selectedPaymentMethod = selectedPaymentMethod,
                isSaveInProgress = isSaveInProgress,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    baseAddress = null,
                    isAddressCopied = false,
                    savedPaymentMethod = CurrencyCode.Inr,
                    selectedPaymentMethod = null,
                    isSaveInProgress = false,
                ),
        )

    private fun createState(
        baseAddress: String?,
        isAddressCopied: Boolean,
        savedPaymentMethod: CurrencyCode,
        selectedPaymentMethod: CurrencyCode?,
        isSaveInProgress: Boolean,
    ): P2pPaymentMethodState {
        val effectiveSelection = selectedPaymentMethod ?: savedPaymentMethod
        return P2pPaymentMethodState(
            baseAddress = baseAddress,
            isAddressCopied = isAddressCopied,
            items =
                P2pPaymentMethod.entries.map { method ->
                    P2pPaymentMethodItemState(
                        method = method,
                        isSelected = method.currency == effectiveSelection,
                        onClick = { onPaymentMethodClick(method) },
                    )
                },
            saveButton =
                ButtonState(
                    text = stringRes(R.string.settings_p2p_payment_method_save),
                    isEnabled = effectiveSelection != savedPaymentMethod && !isSaveInProgress,
                    isLoading = isSaveInProgress,
                    onClick = ::onSaveClick,
                ),
            onCopyBaseAddress = ::onCopyBaseAddress,
            onBack = ::onBack,
        )
    }

    private fun onPaymentMethodClick(method: P2pPaymentMethod) {
        if (method.available) {
            selectedPaymentMethod.update { method.currency }
        }
    }

    private fun onSaveClick() {
        if (isSaveInProgress.value) return
        viewModelScope.launch {
            val selection = selectedPaymentMethod.value ?: return@launch
            val savedSelection = preferredP2pPaymentMethodProvider.get()
            if (selection == savedSelection) {
                selectedPaymentMethod.update { null }
                return@launch
            }

            isSaveInProgress.update { true }
            try {
                preferredP2pPaymentMethodProvider.store(selection)
                selectedPaymentMethod.update { null }
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
