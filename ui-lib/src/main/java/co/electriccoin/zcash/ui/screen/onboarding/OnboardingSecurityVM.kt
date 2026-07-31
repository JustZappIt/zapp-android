package co.electriccoin.zcash.ui.screen.onboarding

import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.preference.EncryptedPreferenceKeys
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.preference.observeAuthMethod
import co.electriccoin.zcash.ui.preference.putAuthMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the onboarding security setup steps: biometric enrollment and 6-digit PIN creation.
 *
 * Biometric path: call [triggerBiometricSetup] → observe [bioState] → advance to Done on [BioState.Success].
 * PIN path: call [savePin] with the confirmed 6-digit string → observe [pinSaved] → advance to Done.
 */
class OnboardingSecurityVM(
    private val biometricRepository: BiometricRepository,
    private val biometricManager: BiometricManager,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : ViewModel() {
    sealed class BioState {
        /** No prompt in flight, no error. */
        object Idle : BioState()

        /** System biometric prompt is showing. */
        object Prompting : BioState()

        /** User authenticated successfully — caller should advance to Done. */
        object Success : BioState()

        /** Authentication failed or hardware unavailable; [message] shown to the user. */
        data class Error(
            val message: StringResource
        ) : BioState()
    }

    private val _bioState = MutableStateFlow<BioState>(BioState.Idle)
    val bioState: StateFlow<BioState> = _bioState.asStateFlow()

    private val _pinSaved = MutableStateFlow(false)
    val pinSaved: StateFlow<Boolean> = _pinSaved.asStateFlow()

    /** Survives process recreation and retains the exact method used by completion UI. */
    val authMethod: StateFlow<AuthMethod?> =
        flow<AuthMethod?> {
            emitAll(standardPreferenceProvider().observeAuthMethod())
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    /** True when the device has at least one biometric enrolled and the hardware is available. */
    val isBiometricAvailable: Boolean
        get() =
            biometricManager.canAuthenticate(biometricRepository.allowedAuthenticators) ==
                BiometricManager.BIOMETRIC_SUCCESS

    fun triggerBiometricSetup() {
        viewModelScope.launch {
            if (!isBiometricAvailable) {
                _bioState.value =
                    BioState.Error(stringRes(R.string.onboarding_bio_error_not_enrolled))
                return@launch
            }
            _bioState.value = BioState.Prompting
            try {
                biometricRepository.requestBiometrics(
                    BiometricRequest(message = stringRes(R.string.onboarding_bio_prompt_message))
                )
                StandardPreferenceKeys.IS_APP_ACCESS_AUTHENTICATION
                    .putValue(standardPreferenceProvider(), true)
                standardPreferenceProvider().putAuthMethod(AuthMethod.BIOMETRIC)
                _bioState.value = BioState.Success
            } catch (_: BiometricsCancelledException) {
                _bioState.value = BioState.Idle
            } catch (_: BiometricsFailureException) {
                _bioState.value = BioState.Error(stringRes(R.string.onboarding_bio_error_verification_failed))
            }
        }
    }

    fun resetBioError() {
        _bioState.value = BioState.Idle
    }

    /**
     * Hashes [pin] with PBKDF2 (per-install salt, 100k iterations) and persists it
     * in encrypted preferences, then marks [IS_APP_ACCESS_AUTHENTICATION] enabled.
     * [pinSaved] flips to `true` once done.
     */
    fun savePin(pin: String) {
        viewModelScope.launch {
            EncryptedPreferenceKeys.APP_PIN_HASH
                .putValue(encryptedPreferenceProvider(), EncryptedPreferenceKeys.hashPinV2(pin))
            StandardPreferenceKeys.IS_APP_ACCESS_AUTHENTICATION
                .putValue(standardPreferenceProvider(), true)
            standardPreferenceProvider().putAuthMethod(AuthMethod.PIN)
            _pinSaved.value = true
        }
    }
}
