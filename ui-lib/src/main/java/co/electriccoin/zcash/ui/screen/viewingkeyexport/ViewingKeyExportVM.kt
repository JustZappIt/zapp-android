package co.electriccoin.zcash.ui.screen.viewingkeyexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetViewingKeyExportDataUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareViewingKeyProfileUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyExportAccount
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyExportResult
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyType
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ViewingKeyExportVM(
    private val getViewingKeyExportData: GetViewingKeyExportDataUseCase,
    private val shareViewingKeyProfile: ShareViewingKeyProfileUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val secretAuthGate: SecretAuthGate,
    private val applicationStateProvider: ApplicationStateProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val snapshot = MutableStateFlow(ViewingKeyExportSnapshot())
    private val isInForeground = MutableStateFlow(false)

    internal val state: StateFlow<ViewingKeyExportState> =
        combine(snapshot, secretAuthGate.pinPrompt) { current, pin ->
            ViewingKeyExportState(
                accounts = current.accounts,
                selectedAccountId = current.selectedAccountId,
                selectedKeyType = current.selectedKeyType,
                isAcknowledged = current.isAcknowledged,
                isLoading = current.isLoading,
                isAuthenticating = current.isAuthenticating,
                isCopied = current.isCopied,
                revealedKey = current.revealedKey,
                error = current.error,
                pinVerify = pin,
                onAccountSelected = ::onAccountSelected,
                onKeyTypeSelected = ::onKeyTypeSelected,
                onAcknowledgementChanged = ::onAcknowledgementChanged,
                onReveal = ::onReveal,
                onCopy = ::onCopy,
                onShare = ::onShare,
                onHide = ::hideKey,
                onBack = ::onBack,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createInitialState(),
        )

    init {
        viewModelScope.launch {
            applicationStateProvider.isInForeground.collect { isForeground ->
                isInForeground.value = isForeground
                if (!isForeground) hideKey()
            }
        }
        viewModelScope.launch { loadAccounts() }
    }

    private suspend fun loadAccounts() {
        runCatching { getViewingKeyExportData.getAccounts() }
            .onSuccess { accounts ->
                val selected = accounts.firstOrNull { it.isSelected } ?: accounts.firstOrNull()
                snapshot.update {
                    it.copy(
                        accounts = accounts,
                        selectedAccountId = selected?.accountId,
                        selectedKeyType = selected?.preferredKeyType() ?: ViewingKeyType.UFVK,
                        isLoading = false,
                    )
                }
            }.onFailure {
                snapshot.update { it.copy(isLoading = false, error = ViewingKeyExportError.LOAD_FAILED) }
            }
    }

    private fun onAccountSelected(accountId: AccountUuid) {
        val account = snapshot.value.accounts.firstOrNull { it.accountId == accountId } ?: return
        snapshot.update {
            it.copy(
                selectedAccountId = accountId,
                selectedKeyType =
                    it.selectedKeyType.takeIf { type -> type in account.availableKeyTypes }
                        ?: account.preferredKeyType(),
                isAcknowledged = false,
                isCopied = false,
                revealedKey = null,
                error = null,
            )
        }
    }

    private fun onKeyTypeSelected(keyType: ViewingKeyType) {
        snapshot.update {
            it.copy(
                selectedKeyType = keyType,
                isAcknowledged = false,
                isCopied = false,
                revealedKey = null,
                error = null,
            )
        }
    }

    private fun onAcknowledgementChanged(isAcknowledged: Boolean) {
        snapshot.update { it.copy(isAcknowledged = isAcknowledged, error = null) }
    }

    private fun onReveal() {
        val current = snapshot.value
        val accountId = current.selectedAccountId
        val selectedAccount = current.accounts.firstOrNull { it.accountId == accountId }
        if (
            accountId != null &&
            selectedAccount != null &&
            current.isAcknowledged &&
            !current.isAuthenticating &&
            current.revealedKey == null &&
            current.selectedKeyType in selectedAccount.availableKeyTypes
        ) {
            viewModelScope.launch {
                snapshot.update { it.copy(isAuthenticating = true, error = null) }
                val authenticated =
                    secretAuthGate.authenticate(stringRes(R.string.viewing_key_export_auth_prompt))
                if (!authenticated) {
                    snapshot.update {
                        it.copy(isAuthenticating = false, error = ViewingKeyExportError.AUTHENTICATION_FAILED)
                    }
                    return@launch
                }

                val result = runCatching { getViewingKeyExportData(accountId, current.selectedKeyType) }.getOrNull()
                snapshot.update {
                    when {
                        !isInForeground.value -> {
                            it.copy(isAuthenticating = false)
                        }

                        result is ViewingKeyExportResult.Available -> {
                            it.copy(isAuthenticating = false, revealedKey = result, error = null)
                        }

                        else -> {
                            it.copy(isAuthenticating = false, error = ViewingKeyExportError.KEY_UNAVAILABLE)
                        }
                    }
                }
            }
        }
    }

    private fun onCopy() {
        val key = snapshot.value.revealedKey?.encodedKey ?: return
        copyToClipboard(key, isSensitive = true)
        snapshot.update { it.copy(isCopied = true) }
        viewModelScope.launch {
            delay(COPY_FEEDBACK_DURATION_MS)
            snapshot.update { it.copy(isCopied = false) }
        }
    }

    private fun onShare(sharePickerText: String) {
        val key = snapshot.value.revealedKey ?: return
        viewModelScope.launch {
            if (!shareViewingKeyProfile(key, sharePickerText)) {
                snapshot.update { it.copy(error = ViewingKeyExportError.SHARE_FAILED) }
            }
        }
    }

    private fun hideKey() {
        snapshot.update { it.copy(revealedKey = null, isCopied = false) }
    }

    private fun onBack() {
        hideKey()
        navigationRouter.back()
    }

    override fun onCleared() {
        hideKey()
        super.onCleared()
    }

    private fun createInitialState() =
        ViewingKeyExportState(
            accounts = emptyList(),
            selectedAccountId = null,
            selectedKeyType = ViewingKeyType.UFVK,
            isAcknowledged = false,
            isLoading = true,
            isAuthenticating = false,
            isCopied = false,
            revealedKey = null,
            error = null,
            pinVerify = null,
            onAccountSelected = {},
            onKeyTypeSelected = {},
            onAcknowledgementChanged = {},
            onReveal = {},
            onCopy = {},
            onShare = {},
            onHide = {},
            onBack = {},
        )

    private companion object {
        const val COPY_FEEDBACK_DURATION_MS = 2_000L
    }
}

private data class ViewingKeyExportSnapshot(
    val accounts: List<ViewingKeyExportAccount> = emptyList(),
    val selectedAccountId: AccountUuid? = null,
    val selectedKeyType: ViewingKeyType = ViewingKeyType.UFVK,
    val isAcknowledged: Boolean = false,
    val isLoading: Boolean = true,
    val isAuthenticating: Boolean = false,
    val isCopied: Boolean = false,
    val revealedKey: ViewingKeyExportResult.Available? = null,
    val error: ViewingKeyExportError? = null,
)

private fun ViewingKeyExportAccount.preferredKeyType(): ViewingKeyType =
    when {
        ViewingKeyType.UFVK in availableKeyTypes -> ViewingKeyType.UFVK
        ViewingKeyType.UIVK in availableKeyTypes -> ViewingKeyType.UIVK
        else -> ViewingKeyType.UFVK
    }
