// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.security

import androidx.biometric.BiometricManager
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretAuthGateTest {
    @Test
    fun biometricSuccessAuthenticates() =
        runTest {
            assertTrue(gateWithBiometricResult(null).gate.authenticate(stringRes("Authenticate")))
        }

    @Test
    fun biometricCancellationDoesNotAuthenticate() =
        runTest {
            assertFalse(
                gateWithBiometricResult(BiometricsCancelledException()).gate.authenticate(stringRes("Authenticate"))
            )
        }

    @Test
    fun biometricFailureDoesNotAuthenticate() =
        runTest {
            assertFalse(
                gateWithBiometricResult(BiometricsFailureException()).gate.authenticate(stringRes("Authenticate"))
            )
        }

    @Test
    fun requiredAuthenticationFailsClosedWhenBiometricIsUnavailable() =
        runTest {
            val fixture = gateWithBiometricResult(null, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)

            assertFalse(
                fixture.gate.authenticate(
                    promptMessage = stringRes("Authenticate"),
                    policy = SecretAuthPolicy.REQUIRE_AUTHENTICATION,
                )
            )
            coVerify(exactly = 0) { fixture.biometricRepository.requestBiometrics(any()) }
        }

    @Test
    fun requiredBiometricSuccessAuthenticates() =
        runTest {
            val fixture = gateWithBiometricResult(null)

            assertTrue(
                fixture.gate.authenticate(
                    promptMessage = stringRes("Authenticate"),
                    policy = SecretAuthPolicy.REQUIRE_AUTHENTICATION,
                )
            )
            coVerify(exactly = 1) { fixture.biometricRepository.requestBiometrics(any()) }
        }

    @Test
    fun requiredAuthenticationFailsClosedWhenBiometricBecomesUnavailable() =
        runTest {
            val fixture =
                gateWithBiometricResult(
                    exception = null,
                    biometricStatuses =
                        listOf(
                            BiometricManager.BIOMETRIC_SUCCESS,
                            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        ),
                )

            assertFalse(
                fixture.gate.authenticate(
                    promptMessage = stringRes("Authenticate"),
                    policy = SecretAuthPolicy.REQUIRE_AUTHENTICATION,
                )
            )
            coVerify(exactly = 1) { fixture.biometricRepository.requestBiometrics(any()) }
        }

    @Test
    fun existingCallerBehaviorIsUnchangedWhenBiometricIsUnavailable() =
        runTest {
            val fixture = gateWithBiometricResult(null, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)

            assertTrue(fixture.gate.authenticate(stringRes("Authenticate")))
            coVerify(exactly = 1) { fixture.biometricRepository.requestBiometrics(any()) }
        }

    @Test
    fun requiredAuthenticationRejectsUnconfiguredWallet() =
        runTest {
            val fixture = gateWithBiometricResult(null, authMethod = "none")

            assertFalse(
                fixture.gate.authenticate(
                    promptMessage = stringRes("Authenticate"),
                    policy = SecretAuthPolicy.REQUIRE_AUTHENTICATION,
                )
            )
        }

    @Test
    fun existingCallersMayAllowUnconfiguredWallet() =
        runTest {
            val fixture = gateWithBiometricResult(null, authMethod = "none")

            assertTrue(fixture.gate.authenticate(stringRes("Authenticate")))
        }

    private fun gateWithBiometricResult(
        exception: Exception?,
        biometricStatus: Int = BiometricManager.BIOMETRIC_SUCCESS,
        biometricStatuses: List<Int> = listOf(biometricStatus),
        authMethod: String = "biometric",
    ): Fixture {
        val preferenceProvider =
            mockk<PreferenceProvider> {
                coEvery { getString(StandardPreferenceKeys.AUTH_METHOD.key) } returns authMethod
            }
        val standardPreferenceProvider = mockk<StandardPreferenceProvider>()
        coEvery { standardPreferenceProvider.invoke() } returns preferenceProvider
        val biometricRepository = mockk<BiometricRepository>(relaxed = true)
        every { biometricRepository.allowedAuthenticators } returns BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (exception == null) {
            coEvery { biometricRepository.requestBiometrics(any()) } returns Unit
        } else {
            coEvery { biometricRepository.requestBiometrics(any()) } throws exception
        }
        val biometricManager = mockk<BiometricManager>()
        val biometricStatusIterator = biometricStatuses.iterator()
        every { biometricManager.canAuthenticate(any<Int>()) } answers {
            if (biometricStatusIterator.hasNext()) biometricStatusIterator.next() else biometricStatuses.last()
        }
        return Fixture(
            gate =
                SecretAuthGate(
                    biometricRepository = biometricRepository,
                    biometricManager = biometricManager,
                    standardPreferenceProvider = standardPreferenceProvider,
                    encryptedPreferenceProvider = mockk<EncryptedPreferenceProvider>(relaxed = true),
                ),
            biometricRepository = biometricRepository,
        )
    }

    private data class Fixture(
        val gate: SecretAuthGate,
        val biometricRepository: BiometricRepository,
    )
}
