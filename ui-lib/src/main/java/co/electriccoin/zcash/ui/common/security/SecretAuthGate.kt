// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.security

import androidx.biometric.BiometricManager
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.preference.getAuthMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PinVerifyState(
    val hasError: Boolean,
    val lockoutSecondsRemaining: Int,
    val onPinSubmit: (String) -> Unit,
    val onCancel: () -> Unit,
)

enum class SecretAuthPolicy {
    ALLOW_UNCONFIGURED,
    REQUIRE_AUTHENTICATION,
}

/**
 * Gates a secret reveal behind whichever app lock the user configured. [pinPrompt] carries the PIN
 * overlay the screen has to render while [authenticate] suspends.
 */
class SecretAuthGate(
    private val biometricRepository: BiometricRepository,
    private val biometricManager: BiometricManager,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
) {
    private val prompt = MutableStateFlow<PinVerifyState?>(null)

    val pinPrompt: StateFlow<PinVerifyState?> = prompt.asStateFlow()

    suspend fun authenticate(
        promptMessage: StringResource,
        policy: SecretAuthPolicy = SecretAuthPolicy.ALLOW_UNCONFIGURED,
    ): Boolean =
        when (standardPreferenceProvider().getAuthMethod()) {
            AuthMethod.BIOMETRIC -> authenticateWithBiometrics(promptMessage, policy)
            AuthMethod.PIN -> authenticateWithPin()
            AuthMethod.NONE -> policy == SecretAuthPolicy.ALLOW_UNCONFIGURED
        }

    private suspend fun authenticateWithBiometrics(
        promptMessage: StringResource,
        policy: SecretAuthPolicy,
    ): Boolean =
        if (
            policy == SecretAuthPolicy.REQUIRE_AUTHENTICATION &&
            !isBiometricAuthenticationAvailable()
        ) {
            false
        } else {
            try {
                biometricRepository.requestBiometrics(BiometricRequest(message = promptMessage))
                policy == SecretAuthPolicy.ALLOW_UNCONFIGURED || isBiometricAuthenticationAvailable()
            } catch (_: BiometricsFailureException) {
                false
            } catch (_: BiometricsCancelledException) {
                false
            }
        }

    private fun isBiometricAuthenticationAvailable(): Boolean =
        biometricManager.canAuthenticate(biometricRepository.allowedAuthenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private suspend fun authenticateWithPin(): Boolean =
        coroutineScope {
            val outcome = CompletableDeferred<Boolean>()
            var lockoutJob: Job? = null

            fun show(
                hasError: Boolean,
                lockoutSecondsRemaining: Int,
            ) {
                prompt.value =
                    PinVerifyState(
                        hasError = hasError,
                        lockoutSecondsRemaining = lockoutSecondsRemaining,
                        onPinSubmit = { pin ->
                            launch {
                                when (val result = verify(pin)) {
                                    PinAuthGate.Result.Success -> {
                                        outcome.complete(true)
                                    }

                                    PinAuthGate.Result.Wrong -> {
                                        show(hasError = true, lockoutSecondsRemaining = 0)
                                        delay(PIN_ERROR_FEEDBACK_MS)
                                        show(hasError = false, lockoutSecondsRemaining = 0)
                                    }

                                    is PinAuthGate.Result.Locked -> {
                                        lockoutJob?.cancel()
                                        lockoutJob = launch { tickLockout(result.msUntilUnlock, ::show) }
                                    }
                                }
                            }
                        },
                        onCancel = { outcome.complete(false) },
                    )
            }

            show(hasError = false, lockoutSecondsRemaining = 0)
            try {
                outcome.await()
            } finally {
                lockoutJob?.cancel()
                prompt.value = null
            }
        }

    private suspend fun verify(pin: String) =
        PinAuthGate.tryVerify(pin, encryptedPreferenceProvider, standardPreferenceProvider)

    private suspend fun tickLockout(
        initialMs: Long,
        show: (Boolean, Int) -> Unit,
    ) {
        var remaining = initialMs
        while (remaining > 0) {
            show(false, ((remaining + MS_ROUND_UP) / MS_PER_SECOND).toInt())
            delay(MS_PER_SECOND)
            remaining -= MS_PER_SECOND
        }
        show(false, 0)
    }

    private companion object {
        const val PIN_ERROR_FEEDBACK_MS = 1_500L
        const val MS_PER_SECOND = 1_000L
        const val MS_ROUND_UP = 999L
    }
}
