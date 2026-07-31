package co.electriccoin.zcash.ui.screen.securitysettings

import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.security.PinAuthGate
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.preference.EncryptedPreferenceKeys
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.preference.getAuthMethod
import co.electriccoin.zcash.ui.preference.putAuthMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PinVerifyIntent { ChangePinSetNew, SwitchToBiometric }

enum class NewPinIntent { ChangeExisting, SwitchFromBiometric }

sealed class SecuritySettingsState {
    /** Hub: shows current method, tab selector, action row, and Save Changes dock. */
    data class Menu(
        val currentMethod: AuthMethod,
        val selectedTab: AuthMethod,
        val isBioAvailable: Boolean,
        val successMessage: StringResource? = null,
    ) : SecuritySettingsState()

    /** Verifying the existing PIN before advancing to the next step. */
    data class VerifyingCurrentPin(
        val intent: PinVerifyIntent
    ) : SecuritySettingsState()

    /** Two-phase new PIN entry (settings context — no onboarding chrome). */
    data class SettingNewPin(
        val intent: NewPinIntent
    ) : SecuritySettingsState()

    /** Biometric enrollment prompt. */
    data object SettingNewBio : SecuritySettingsState()
}

class SecuritySettingsVM(
    private val biometricRepository: BiometricRepository,
    private val biometricManager: BiometricManager,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow<SecuritySettingsState>(
            SecuritySettingsState.Menu(
                currentMethod = AuthMethod.PIN,
                selectedTab = AuthMethod.PIN,
                isBioAvailable = false,
            )
        )
    val uiState: StateFlow<SecuritySettingsState> = _uiState.asStateFlow()

    private val _pinError = MutableStateFlow(false)
    val pinError: StateFlow<Boolean> = _pinError.asStateFlow()

    private val _pinLockoutSeconds = MutableStateFlow(0)
    val pinLockoutSeconds: StateFlow<Int> = _pinLockoutSeconds.asStateFlow()

    private val _bioError = MutableStateFlow<StringResource?>(null)
    val bioError: StateFlow<StringResource?> = _bioError.asStateFlow()

    private val _isEnrollingBio = MutableStateFlow(false)
    val isEnrollingBio: StateFlow<Boolean> = _isEnrollingBio.asStateFlow()

    init {
        viewModelScope.launch {
            val method = standardPreferenceProvider().getAuthMethod()
            _uiState.value =
                SecuritySettingsState.Menu(
                    currentMethod = method,
                    selectedTab = method,
                    isBioAvailable = checkBioAvailable(),
                )
            val lockMs = PinAuthGate.msUntilUnlock(standardPreferenceProvider)
            if (lockMs > 0) {
                _pinLockoutSeconds.value = (lockMs / 1000L).toInt().coerceAtLeast(1)
                launchLockoutCountdown()
            }
        }
    }

    private fun checkBioAvailable() =
        biometricManager.canAuthenticate(biometricRepository.allowedAuthenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun launchLockoutCountdown() {
        viewModelScope.launch {
            while (true) {
                val ms = PinAuthGate.msUntilUnlock(standardPreferenceProvider)
                _pinLockoutSeconds.value = if (ms > 0) (ms / 1000L).toInt().coerceAtLeast(1) else 0
                if (ms <= 0) break
                delay(1000)
            }
        }
    }

    /** Called when the user taps a tab in the segmented selector on the hub. */
    fun onTabSelected(tab: AuthMethod) {
        val current = _uiState.value as? SecuritySettingsState.Menu ?: return
        _uiState.value = current.copy(selectedTab = tab, successMessage = null)
    }

    /**
     * Primary action — triggered by both the "Save Changes" dock button AND the action row tap.
     *
     * Behaviour depends on current method vs selected tab:
     * - PIN → PIN: verify current PIN, then set a new one (Change PIN)
     * - BIO → BIO: re-enroll biometrics directly
     * - PIN → BIO: verify current PIN first, then biometric enrollment
     * - BIO → PIN: verify with the active biometric first, then new PIN setup
     *
     * Every transition re-authenticates with the currently-active credential before any change,
     * so momentary access to an unlocked app can't be used to silently take over the app-lock.
     */
    fun onSaveChanges() {
        val menu = _uiState.value as? SecuritySettingsState.Menu ?: return
        when {
            menu.selectedTab == AuthMethod.PIN && menu.currentMethod == AuthMethod.PIN -> {
                _pinError.value = false
                _uiState.value = SecuritySettingsState.VerifyingCurrentPin(PinVerifyIntent.ChangePinSetNew)
            }

            menu.selectedTab == AuthMethod.PIN && menu.currentMethod == AuthMethod.BIOMETRIC -> {
                verifyBiometricThenSwitchToPin()
            }

            menu.selectedTab == AuthMethod.BIOMETRIC && menu.currentMethod == AuthMethod.PIN -> {
                _pinError.value = false
                _uiState.value = SecuritySettingsState.VerifyingCurrentPin(PinVerifyIntent.SwitchToBiometric)
            }

            menu.selectedTab == AuthMethod.BIOMETRIC && menu.currentMethod == AuthMethod.BIOMETRIC -> {
                triggerBioEnrollment()
            }
        }
    }

    /**
     * BIO → PIN re-auth gate: the user must pass the currently-active biometric before they are
     * allowed to set a brand-new PIN and flip the active unlock method. Mirrors the PIN paths
     * that gate on [submitCurrentPin]. On cancel/failure the active method is left unchanged.
     */
    private fun verifyBiometricThenSwitchToPin() {
        // requestBiometrics() is a silent no-op (no prompt, no exception) when the device can't
        // authenticate at all — biometrics AND device credential both unavailable. Gate on that
        // here so a silent return can never fall through to an unauthenticated PIN change.
        if (!checkBioAvailable()) return
        viewModelScope.launch {
            try {
                biometricRepository.requestBiometrics(
                    BiometricRequest(message = stringRes(R.string.security_settings_bio_verify_switch_pin_prompt))
                )
                _uiState.value = SecuritySettingsState.SettingNewPin(NewPinIntent.SwitchFromBiometric)
            } catch (_: BiometricsCancelledException) {
                // User backed out — leave the active unlock method unchanged.
            } catch (_: BiometricsFailureException) {
                // Verification failed — do not let an unverified caller change the credential.
            }
        }
    }

    /** Handles the PIN submitted on the verify screen. */
    fun submitCurrentPin(pin: String) {
        viewModelScope.launch {
            when (val result = PinAuthGate.tryVerify(pin, encryptedPreferenceProvider, standardPreferenceProvider)) {
                PinAuthGate.Result.Success -> {
                    _pinError.value = false
                    val current = _uiState.value as? SecuritySettingsState.VerifyingCurrentPin ?: return@launch
                    _uiState.value =
                        when (current.intent) {
                            PinVerifyIntent.ChangePinSetNew -> SecuritySettingsState.SettingNewPin(NewPinIntent.ChangeExisting)
                            PinVerifyIntent.SwitchToBiometric -> SecuritySettingsState.SettingNewBio
                        }
                }

                PinAuthGate.Result.Wrong -> {
                    _pinError.value = true
                }

                is PinAuthGate.Result.Locked -> {
                    _pinLockoutSeconds.value = (result.msUntilUnlock / 1000L).toInt().coerceAtLeast(1)
                    launchLockoutCountdown()
                }
            }
        }
    }

    /** Called when the user completes the new PIN entry + confirmation. */
    fun onNewPinConfirmed(newPin: String) {
        viewModelScope.launch {
            val settingState = _uiState.value as? SecuritySettingsState.SettingNewPin ?: return@launch
            EncryptedPreferenceKeys.APP_PIN_HASH.putValue(
                encryptedPreferenceProvider(),
                EncryptedPreferenceKeys.hashPinV2(newPin),
            )
            if (settingState.intent == NewPinIntent.SwitchFromBiometric) {
                standardPreferenceProvider().putAuthMethod(AuthMethod.PIN)
            }
            val method = standardPreferenceProvider().getAuthMethod()
            _uiState.value =
                SecuritySettingsState.Menu(
                    currentMethod = method,
                    selectedTab = method,
                    isBioAvailable = checkBioAvailable(),
                    successMessage =
                        when (settingState.intent) {
                            NewPinIntent.SwitchFromBiometric -> stringRes(R.string.security_settings_success_switched_pin)
                            NewPinIntent.ChangeExisting -> stringRes(R.string.security_settings_success_pin_changed)
                        },
                )
        }
    }

    private fun triggerBioEnrollment() {
        viewModelScope.launch {
            _isEnrollingBio.value = true
            _bioError.value = null
            try {
                biometricRepository.requestBiometrics(
                    BiometricRequest(message = stringRes(R.string.onboarding_bio_prompt_message))
                )
                val prefs = standardPreferenceProvider()
                prefs.putAuthMethod(AuthMethod.BIOMETRIC)
                EncryptedPreferenceKeys.APP_PIN_HASH.putValue(encryptedPreferenceProvider(), "")
                StandardPreferenceKeys.FAILED_PIN_ATTEMPTS_COUNT.putValue(prefs, 0)
                StandardPreferenceKeys.PIN_LOCKOUT_END_WALLTIME_MS.putValue(prefs, 0L)
                val method = AuthMethod.BIOMETRIC
                _uiState.value =
                    SecuritySettingsState.Menu(
                        currentMethod = method,
                        selectedTab = method,
                        isBioAvailable = checkBioAvailable(),
                        successMessage = stringRes(R.string.security_settings_success_bio_enrolled),
                    )
            } catch (_: BiometricsCancelledException) {
                val method = standardPreferenceProvider().getAuthMethod()
                _uiState.value =
                    SecuritySettingsState.Menu(
                        currentMethod = method,
                        selectedTab = method,
                        isBioAvailable = checkBioAvailable(),
                    )
            } catch (_: BiometricsFailureException) {
                _bioError.value = stringRes(R.string.security_settings_bio_enroll_failed)
            } finally {
                _isEnrollingBio.value = false
            }
        }
    }

    /** Called when the bio enrollment CTA is tapped on the BioScanScreen. */
    fun onBioEnroll() {
        triggerBioEnrollment()
    }

    fun clearSuccessMessage() {
        val current = _uiState.value as? SecuritySettingsState.Menu ?: return
        if (current.successMessage != null) {
            _uiState.value = current.copy(successMessage = null)
        }
    }

    fun resetBioError() {
        _bioError.value = null
    }

    fun onBack() {
        when (_uiState.value) {
            is SecuritySettingsState.Menu -> {
                navigationRouter.back()
            }

            else -> {
                viewModelScope.launch {
                    val method = standardPreferenceProvider().getAuthMethod()
                    _uiState.value =
                        SecuritySettingsState.Menu(
                            currentMethod = method,
                            selectedTab = method,
                            isBioAvailable = checkBioAvailable(),
                        )
                    _pinError.value = false
                    _bioError.value = null
                }
            }
        }
    }
}
