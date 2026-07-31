// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.profile

import android.app.Application
import android.content.Intent
import android.os.Process
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.security.PinAuthGate
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.DeleteChatIdentityUseCase
import co.electriccoin.zcash.ui.common.usecase.ExportChatSeedPhraseUseCase
import co.electriccoin.zcash.ui.common.usecase.ExportP2pWalletKeyUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOfframpBaseAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveChatIdentityUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.P2pWalletKey
import co.electriccoin.zcash.ui.common.usecase.UpdateChatDisplayNameUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.preference.getAuthMethod
import co.electriccoin.zcash.ui.screen.chat.common.ChatResult
import co.electriccoin.zcash.ui.screen.chat.common.UsernameRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class ChatProfileVM(
    private val application: Application,
    private val copyToClipboard: CopyToClipboardUseCase,
    observeChatIdentity: ObserveChatIdentityUseCase,
    private val updateChatDisplayName: UpdateChatDisplayNameUseCase,
    private val deleteChatIdentity: DeleteChatIdentityUseCase,
    private val exportChatSeedPhrase: ExportChatSeedPhraseUseCase,
    private val exportP2pWalletKey: ExportP2pWalletKeyUseCase,
    private val getOfframpBaseAddress: GetOfframpBaseAddressUseCase,
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val biometricRepository: BiometricRepository,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val activeTab = MutableStateFlow(ChatProfileTab.MESSAGING_ID)
    private val walletSubTab = MutableStateFlow(ChatProfileWalletSubTab.SHIELDED)
    private val isKeyCopied = MutableStateFlow(false)
    private val isAddressCopied = MutableStateFlow(false)
    private val showDeleteDialog = MutableStateFlow(false)
    private val showEditNameDialog = MutableStateFlow(false)
    private val editNameInput = MutableStateFlow("")
    private val isUpdatingDisplayName = MutableStateFlow(false)
    private val editNameError = MutableStateFlow<StringResource?>(null)
    private val pinVerifyMode = MutableStateFlow<PinVerifyMode>(PinVerifyMode.Idle)
    private val pendingSeedPhrase = MutableStateFlow<String?>(null)
    private val pendingP2pKey = MutableStateFlow<P2pWalletKey?>(null)
    private val baseAddress = MutableStateFlow<String?>(null)
    private val isBaseAddressCopied = MutableStateFlow(false)

    private var revealTarget = RevealTarget.SEED_PHRASE

    private val walletAccount =
        observeSelectedWalletAccount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    private val identity =
        observeChatIdentity()
            .map { id -> id?.let { ChatProfileIdentity(displayName = it.displayName, publicKey = it.publicKey) } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    private var pinLockoutTickerJob: Job? = null
    private var copyKeyResetJob: Job? = null
    private var copyAddressResetJob: Job? = null
    private var copyBaseAddressResetJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { getOfframpBaseAddress() }
                .onSuccess { addr -> baseAddress.value = addr }
                .onFailure { Twig.warn(it) { "ChatProfileVM: base address resolve failed" } }
        }
    }

    val state: StateFlow<ChatProfileState> =
        combine(
            combine(activeTab, walletSubTab, identity) { tab, sub, id -> Triple(tab, sub, id) },
            combine(walletAccount, baseAddress, isBaseAddressCopied) { wallet, base, baseCopied ->
                Triple(wallet, base, baseCopied)
            },
            combine(isKeyCopied, isAddressCopied) { key, addr -> key to addr },
            combine(
                showDeleteDialog,
                showEditNameDialog,
                editNameInput,
                isUpdatingDisplayName,
                editNameError,
            ) { delete, edit, input, isSaving, error ->
                EditNameSnapshot(delete, edit, input, isSaving, error)
            },
            combine(pinVerifyMode, pendingSeedPhrase, pendingP2pKey) { pin, seed, p2p -> Triple(pin, seed, p2p) },
        ) {
            (tab, sub, id),
            (wallet, baseAddr, baseCopied),
            (keyCopied, addrCopied),
            editName,
            (pinMode, seed, p2pKey)
            ->
            createState(
                tab,
                sub,
                id,
                wallet,
                baseAddr,
                baseCopied,
                keyCopied,
                addrCopied,
                editName.showDelete,
                editName.showEdit,
                editName.input,
                editName.isSaving,
                editName.error,
                pinMode,
                seed,
                p2pKey,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    tab = ChatProfileTab.MESSAGING_ID,
                    sub = ChatProfileWalletSubTab.SHIELDED,
                    id = null,
                    wallet = null,
                    baseAddr = null,
                    baseAddrCopied = false,
                    keyCopied = false,
                    addrCopied = false,
                    delDlg = false,
                    editDlg = false,
                    editInput = "",
                    isSavingDisplayName = false,
                    editNameError = null,
                    pinMode = PinVerifyMode.Idle,
                    seed = null,
                    p2pKey = null,
                ),
        )

    private fun createState(
        tab: ChatProfileTab,
        sub: ChatProfileWalletSubTab,
        id: ChatProfileIdentity?,
        wallet: WalletAccount?,
        baseAddr: String?,
        baseAddrCopied: Boolean,
        keyCopied: Boolean,
        addrCopied: Boolean,
        delDlg: Boolean,
        editDlg: Boolean,
        editInput: String,
        isSavingDisplayName: Boolean,
        editNameError: StringResource?,
        pinMode: PinVerifyMode,
        seed: String?,
        p2pKey: P2pWalletKey?,
    ): ChatProfileState =
        ChatProfileState(
            title = stringRes(R.string.chat_profile_title),
            activeTab = tab,
            walletSubTab = sub,
            displayName = id?.displayName,
            publicKey = id?.publicKey,
            shieldedAddress = wallet?.unified?.address?.address,
            transparentAddress = wallet?.transparent?.address?.address,
            baseAddress = baseAddr,
            isKeyCopied = keyCopied,
            isAddressCopied = addrCopied,
            isBaseAddressCopied = baseAddrCopied,
            onMainTabSelected = ::onMainTabSelected,
            onWalletSubTabSelected = ::onWalletSubTabSelected,
            onEditDisplayNameClick = ::onEditDisplayNameClick,
            onCopyPublicKeyClick = ::onCopyPublicKeyClick,
            onCopyAddressClick = ::onCopyAddressClick,
            onCopyBaseAddressClick = ::onCopyBaseAddressClick,
            onSeedPhraseClick = ::onSeedPhraseClick,
            onP2pKeyClick = ::onP2pKeyClick,
            onDeleteClick = ::onDeleteClick,
            onBack = ::onBack,
            editNameDialog =
                if (editDlg) {
                    ChatProfileEditNameDialogState(
                        value = editInput,
                        canSave = UsernameRules.isValid(editInput) && !isSavingDisplayName,
                        isSaving = isSavingDisplayName,
                        error = editNameError,
                        onValueChange = ::onEditNameInputChange,
                        onSave = ::onEditNameSave,
                        onDismiss = ::dismissEditNameDialog,
                    )
                } else {
                    null
                },
            deleteDialog =
                if (delDlg) {
                    ChatProfileDeleteDialogState(
                        onConfirm = ::onDeleteConfirm,
                        onDismiss = ::dismissDeleteDialog,
                    )
                } else {
                    null
                },
            seedPhraseDialog =
                seed?.let { phrase ->
                    ChatProfileSeedPhraseDialogState(
                        words = phrase.split(" ").filter { it.isNotBlank() },
                        onDismiss = ::dismissSeedPhraseDialog,
                    )
                },
            p2pKeyDialog =
                p2pKey?.let { key ->
                    ChatProfileP2pKeyDialogState(
                        address = key.address,
                        privateKeyHex = key.privateKeyHex,
                        onCopyAddress = { copyToClipboard(key.address) },
                        onCopyPrivateKey = { copyToClipboard(key.privateKeyHex) },
                        onDismiss = ::dismissP2pKeyDialog,
                    )
                },
            pinVerify = pinMode.toState(),
        )

    private fun PinVerifyMode.toState(): ChatProfilePinVerifyState? =
        when (this) {
            PinVerifyMode.Idle -> {
                null
            }

            PinVerifyMode.Required -> {
                ChatProfilePinVerifyState(
                    hasError = false,
                    lockoutSecondsRemaining = 0,
                    onPinSubmit = ::onPinSubmitted,
                    onCancel = ::onPinEntryDismissed,
                )
            }

            PinVerifyMode.Error -> {
                ChatProfilePinVerifyState(
                    hasError = true,
                    lockoutSecondsRemaining = 0,
                    onPinSubmit = ::onPinSubmitted,
                    onCancel = ::onPinEntryDismissed,
                )
            }

            is PinVerifyMode.Locked -> {
                ChatProfilePinVerifyState(
                    hasError = false,
                    lockoutSecondsRemaining = secondsRemaining,
                    onPinSubmit = ::onPinSubmitted,
                    onCancel = ::onPinEntryDismissed,
                )
            }
        }

    // ── Click handlers ─────────────────────────────────────────────────

    private fun onBack() = navigationRouter.back()

    private fun onMainTabSelected(tab: ChatProfileTab) {
        activeTab.value = tab
    }

    private fun onWalletSubTabSelected(tab: ChatProfileWalletSubTab) {
        walletSubTab.value = tab
    }

    private fun onEditDisplayNameClick() {
        editNameInput.value = UsernameRules.sanitize(identity.value?.displayName.orEmpty())
        editNameError.value = null
        showEditNameDialog.value = true
    }

    private fun onEditNameInputChange(value: String) {
        editNameInput.value = UsernameRules.sanitize(value)
        editNameError.value = null
    }

    private fun onEditNameSave() {
        val trimmed = editNameInput.value.trim()
        if (!UsernameRules.isValid(trimmed) || isUpdatingDisplayName.value) return
        isUpdatingDisplayName.value = true
        editNameError.value = null
        viewModelScope.launch {
            try {
                updateChatDisplayName(trimmed)
                    .onSuccess { showEditNameDialog.value = false }
                    .onFailure { editNameError.value = stringRes(R.string.chat_display_name_update_error) }
            } finally {
                isUpdatingDisplayName.value = false
            }
        }
    }

    private fun dismissEditNameDialog() {
        if (isUpdatingDisplayName.value) return
        showEditNameDialog.value = false
        editNameError.value = null
    }

    private fun onDeleteClick() {
        showDeleteDialog.value = true
    }

    private fun dismissDeleteDialog() {
        showDeleteDialog.value = false
    }

    private fun onDeleteConfirm() {
        showDeleteDialog.value = false
        viewModelScope.launch { performDeleteIdentity() }
    }

    private suspend fun performDeleteIdentity() {
        deleteChatIdentity()
        application.packageManager.getLaunchIntentForPackage(application.packageName)?.let { intent ->
            intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            application.startActivity(intent)
        }
        Process.killProcess(Process.myPid())
    }

    private fun onCopyPublicKeyClick() {
        val pk = identity.value?.publicKey ?: return
        copyToClipboard(pk)
        isKeyCopied.value = true
        copyKeyResetJob?.cancel()
        copyKeyResetJob =
            viewModelScope.launch {
                delay(COPY_FEEDBACK_MS)
                isKeyCopied.value = false
            }
    }

    private fun onCopyAddressClick() {
        val address = currentWalletAddress() ?: return
        copyToClipboard(address)
        isAddressCopied.value = true
        copyAddressResetJob?.cancel()
        copyAddressResetJob =
            viewModelScope.launch {
                delay(COPY_FEEDBACK_MS)
                isAddressCopied.value = false
            }
    }

    private fun onCopyBaseAddressClick() {
        val address = baseAddress.value ?: return
        copyToClipboard(address)
        isBaseAddressCopied.value = true
        copyBaseAddressResetJob?.cancel()
        copyBaseAddressResetJob =
            viewModelScope.launch {
                delay(COPY_FEEDBACK_MS)
                isBaseAddressCopied.value = false
            }
    }

    private fun currentWalletAddress(): String? {
        val account = walletAccount.value ?: return null
        return when (walletSubTab.value) {
            ChatProfileWalletSubTab.SHIELDED -> account.unified.address.address
            ChatProfileWalletSubTab.TRANSPARENT -> account.transparent.address.address
        }
    }

    // ── Secret reveal (PIN / biometric gate) ────────────────────────────

    private fun onSeedPhraseClick() {
        revealTarget = RevealTarget.SEED_PHRASE
        viewModelScope.launch { initiateReveal() }
    }

    private fun onP2pKeyClick() {
        revealTarget = RevealTarget.P2P_KEY
        viewModelScope.launch { initiateReveal() }
    }

    private suspend fun initiateReveal() {
        val authMethod = standardPreferenceProvider().getAuthMethod()
        when (authMethod) {
            AuthMethod.BIOMETRIC -> {
                try {
                    biometricRepository.requestBiometrics(
                        BiometricRequest(message = stringRes(revealTarget.biometricPromptRes)),
                    )
                    performReveal()
                } catch (_: BiometricsFailureException) {
                    // user dismissed / hardware failed — silent
                } catch (_: BiometricsCancelledException) {
                    // explicit cancel — silent
                }
            }

            AuthMethod.PIN -> {
                pinVerifyMode.value = PinVerifyMode.Required
            }

            AuthMethod.NONE -> {
                performReveal()
            }
        }
    }

    private suspend fun performReveal() {
        when (revealTarget) {
            RevealTarget.SEED_PHRASE -> exportAndEmitSeedPhrase()
            RevealTarget.P2P_KEY -> exportAndEmitP2pKey()
        }
    }

    private fun onPinSubmitted(pin: String) {
        viewModelScope.launch {
            val result =
                PinAuthGate.tryVerify(pin, encryptedPreferenceProvider, standardPreferenceProvider)
            when (result) {
                PinAuthGate.Result.Success -> {
                    pinVerifyMode.value = PinVerifyMode.Idle
                    performReveal()
                }

                PinAuthGate.Result.Wrong -> {
                    pinVerifyMode.value = PinVerifyMode.Error
                    delay(PIN_ERROR_FEEDBACK_MS)
                    pinVerifyMode.value = PinVerifyMode.Required
                }

                is PinAuthGate.Result.Locked -> {
                    startPinLockoutTicker(result.msUntilUnlock)
                }
            }
        }
    }

    private fun onPinEntryDismissed() {
        pinVerifyMode.value = PinVerifyMode.Idle
    }

    private fun startPinLockoutTicker(initialMs: Long) {
        pinLockoutTickerJob?.cancel()
        pinLockoutTickerJob =
            viewModelScope.launch {
                var remaining = initialMs
                while (remaining > 0) {
                    pinVerifyMode.value =
                        PinVerifyMode.Locked(((remaining + MS_ROUND_UP) / MS_PER_SECOND).toInt())
                    delay(MS_PER_SECOND)
                    remaining -= MS_PER_SECOND
                }
                pinVerifyMode.value = PinVerifyMode.Required
            }
    }

    private suspend fun exportAndEmitSeedPhrase() {
        val result = exportChatSeedPhrase()
        if (result is ChatResult.Success) pendingSeedPhrase.value = result.value
    }

    private fun dismissSeedPhraseDialog() {
        pendingSeedPhrase.value = null
    }

    private suspend fun exportAndEmitP2pKey() {
        runCatching { exportP2pWalletKey() }
            .onSuccess { pendingP2pKey.value = it }
            .onFailure { Twig.warn(it) { "ChatProfileVM: P2P wallet key export failed" } }
    }

    private fun dismissP2pKeyDialog() {
        pendingP2pKey.value = null
    }

    private enum class RevealTarget(
        @param:StringRes val biometricPromptRes: Int
    ) {
        SEED_PHRASE(R.string.chat_profile_seed_phrase_biometric_prompt),
        P2P_KEY(R.string.chat_profile_p2p_key_biometric_prompt),
    }

    private sealed class PinVerifyMode {
        object Idle : PinVerifyMode()

        object Required : PinVerifyMode()

        object Error : PinVerifyMode()

        data class Locked(
            val secondsRemaining: Int
        ) : PinVerifyMode()
    }

    private data class ChatProfileIdentity(
        val displayName: String,
        val publicKey: String
    )

    private data class EditNameSnapshot(
        val showDelete: Boolean,
        val showEdit: Boolean,
        val input: String,
        val isSaving: Boolean,
        val error: StringResource?,
    )

    companion object {
        private const val COPY_FEEDBACK_MS = 2_000L
        private const val PIN_ERROR_FEEDBACK_MS = 1_500L
        private const val MS_PER_SECOND = 1_000L
        private const val MS_ROUND_UP = 999L
    }

    override fun onCleared() {
        super.onCleared()
        pinLockoutTickerJob?.cancel()
        copyKeyResetJob?.cancel()
        copyAddressResetJob?.cancel()
        copyBaseAddressResetJob?.cancel()
    }
}
