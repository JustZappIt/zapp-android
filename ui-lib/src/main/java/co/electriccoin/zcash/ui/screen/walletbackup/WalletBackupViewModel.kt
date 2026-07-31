package co.electriccoin.zcash.ui.screen.walletbackup

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.security.PinAuthGate
import co.electriccoin.zcash.ui.common.usecase.GetPersistableWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.OnUserSavedWalletBackupUseCase
import co.electriccoin.zcash.ui.common.usecase.RemindWalletBackupLaterUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletBackupData
import co.electriccoin.zcash.ui.common.usecase.WalletBackupMessageUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.SeedTextState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.preference.getAuthMethod
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WalletBackupViewModel(
    walletBackupMessageUseCase: WalletBackupMessageUseCase,
    getPersistableWallet: GetPersistableWalletUseCase,
    private val args: WalletBackup,
    private val navigationRouter: NavigationRouter,
    private val onUserSavedWalletBackup: OnUserSavedWalletBackupUseCase,
    private val remindWalletBackupLater: RemindWalletBackupLaterUseCase,
    private val biometricRepository: BiometricRepository,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : ViewModel() {
    /** Drives the PIN entry overlay shown over the seed screen when auth method is PIN. */
    sealed class PinVerifyState {
        object Idle : PinVerifyState()

        object Required : PinVerifyState()

        object Error : PinVerifyState()

        /** Lockout in effect — input must be disabled and a countdown shown. */
        data class Locked(
            val secondsRemaining: Int
        ) : PinVerifyState()
    }

    private val _pinVerifyState = MutableStateFlow<PinVerifyState>(PinVerifyState.Idle)
    val pinVerifyState: StateFlow<PinVerifyState> = _pinVerifyState.asStateFlow()
    private var pinLockoutTickerJob: Job? = null

    private fun startPinLockoutTicker(initialMs: Long) {
        pinLockoutTickerJob?.cancel()
        pinLockoutTickerJob =
            viewModelScope.launch {
                var remaining = initialMs
                while (remaining > 0) {
                    _pinVerifyState.value = PinVerifyState.Locked(((remaining + 999) / 1000).toInt())
                    delay(1_000)
                    remaining -= 1_000
                }
                _pinVerifyState.value = PinVerifyState.Required
            }
    }

    private val lockoutDuration =
        walletBackupMessageUseCase
            .observe()
            .filterIsInstance<WalletBackupData.Available>()
            .take(1)
            .map { it.lockoutDuration }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null
            )

    private val isRevealed = MutableStateFlow(false)

    private val isRemindMeLaterButtonVisible =
        isRevealed
            .map { isRevealed ->
                isRevealed && args.isOpenedFromSeedBackupInfo
            }

    val state =
        combine(
            isRevealed,
            isRemindMeLaterButtonVisible,
            getPersistableWallet.observe(),
            lockoutDuration
        ) { isRevealed, isRemindMeLaterButtonVisible, wallet, lockoutDuration ->
            WalletBackupState(
                secondaryButton =
                    ButtonState(
                        text =
                            if (lockoutDuration != null) {
                                stringRes(R.string.general_remind_me_in, stringRes(lockoutDuration.res))
                            } else {
                                stringRes(R.string.general_remind_me_later)
                            },
                        onClick = ::onRemindMeLaterClick
                    ).takeIf { isRemindMeLaterButtonVisible },
                primaryButton =
                    ButtonState(
                        text =
                            when {
                                isRevealed && args.isOpenedFromSeedBackupInfo -> {
                                    stringRes(R.string.seed_recovery_saved_button)
                                }

                                isRevealed -> {
                                    stringRes(R.string.seed_recovery_hide_button)
                                }

                                else -> {
                                    stringRes(R.string.seed_recovery_reveal_button)
                                }
                            },
                        onClick =
                            if (isRevealed && args.isOpenedFromSeedBackupInfo) {
                                { onWalletBackupSavedClick() }
                            } else {
                                { onRevealClick() }
                            },
                        isEnabled = wallet != null,
                        isLoading = wallet == null,
                        icon =
                            when {
                                isRevealed && args.isOpenedFromSeedBackupInfo -> null
                                isRevealed -> R.drawable.ic_seed_hide
                                else -> R.drawable.ic_seed_show
                            },
                        hapticFeedbackType = if (isRevealed) HapticFeedbackType.Confirm else null
                    ),
                info =
                    IconButtonState(
                        onClick = ::onInfoClick,
                        icon = R.drawable.ic_help
                    ),
                seed =
                    SeedTextState(
                        seed = wallet?.seedPhrase?.joinToString().orEmpty(),
                        isRevealed = isRevealed,
                    ),
                birthday =
                    SeedSecretState(
                        title = stringRes(R.string.seed_recovery_bday_title),
                        text =
                            stringRes(
                                wallet
                                    ?.birthday
                                    ?.value
                                    ?.toString()
                                    .orEmpty()
                            ),
                        isRevealed = isRevealed,
                        tooltip =
                            SeedSecretStateTooltip(
                                title = stringRes(R.string.seed_recovery_bday_tooltip_title),
                                message = stringRes(R.string.seed_recovery_bday_tooltip_message)
                            ),
                        onClick = null,
                    ),
                onBack = ::onBack
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

    private fun onRemindMeLaterClick() = viewModelScope.launch { remindWalletBackupLater(persistConsent = false) }

    private fun onWalletBackupSavedClick() =
        viewModelScope.launch {
            onUserSavedWalletBackup()
        }

    private fun onRevealClick() =
        viewModelScope.launch {
            if (!isRevealed.value) {
                val authMethod = standardPreferenceProvider().getAuthMethod()
                when (authMethod) {
                    AuthMethod.BIOMETRIC -> {
                        try {
                            biometricRepository.requestBiometrics(
                                BiometricRequest(
                                    message =
                                        stringRes(
                                            R.string.authentication_system_ui_subtitle,
                                            stringRes(R.string.authentication_use_case_seed_recovery)
                                        )
                                )
                            )
                            isRevealed.update { !it }
                        } catch (_: BiometricsFailureException) {
                            // User failed biometric — stay hidden
                        } catch (_: BiometricsCancelledException) {
                            // User cancelled — stay hidden
                        }
                    }

                    AuthMethod.PIN -> {
                        _pinVerifyState.value = PinVerifyState.Required
                    }

                    AuthMethod.NONE -> {
                        // No auth configured — reveal directly
                        isRevealed.update { !it }
                    }
                }
            } else {
                isRevealed.update { !it }
            }
        }

    /**
     * Called by the UI when the user submits a PIN on the [PinVerifyState.Required]
     * overlay. Verifies through [PinAuthGate] (shared global lockout); on success
     * reveals the seed phrase.
     */
    fun onPinSubmitted(pin: String) {
        viewModelScope.launch {
            when (
                val result =
                    PinAuthGate.tryVerify(
                        pin,
                        encryptedPreferenceProvider,
                        standardPreferenceProvider,
                    )
            ) {
                PinAuthGate.Result.Success -> {
                    isRevealed.value = true
                    _pinVerifyState.value = PinVerifyState.Idle
                }

                PinAuthGate.Result.Wrong -> {
                    _pinVerifyState.value = PinVerifyState.Error
                    delay(1_500)
                    _pinVerifyState.value = PinVerifyState.Required
                }

                is PinAuthGate.Result.Locked -> {
                    startPinLockoutTicker(result.msUntilUnlock)
                }
            }
        }
    }

    /** Called by the UI when the user cancels out of the PIN overlay. */
    fun onPinEntryDismissed() {
        // Cancel the lockout ticker; otherwise its next tick re-shows the PIN overlay and traps the user.
        pinLockoutTickerJob?.cancel()
        _pinVerifyState.value = PinVerifyState.Idle
    }

    private fun onInfoClick() {
        navigationRouter.forward(SeedInfo)
    }

    private fun onBack() = navigationRouter.back()
}
