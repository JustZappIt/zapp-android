package co.electriccoin.zcash.ui.common.security

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretAuthGateTest {
    @Test
    fun biometricSuccessAuthenticates() =
        runTest {
            assertTrue(gateWithBiometricResult(null).authenticate(stringRes("Authenticate")))
        }

    @Test
    fun biometricCancellationDoesNotAuthenticate() =
        runTest {
            assertFalse(
                gateWithBiometricResult(BiometricsCancelledException()).authenticate(stringRes("Authenticate"))
            )
        }

    @Test
    fun biometricFailureDoesNotAuthenticate() =
        runTest {
            assertFalse(gateWithBiometricResult(BiometricsFailureException()).authenticate(stringRes("Authenticate")))
        }

    private fun gateWithBiometricResult(exception: Exception?): SecretAuthGate {
        val preferenceProvider =
            mockk<PreferenceProvider> {
                coEvery { getString(StandardPreferenceKeys.AUTH_METHOD.key) } returns "biometric"
            }
        val standardPreferenceProvider = mockk<StandardPreferenceProvider>()
        coEvery { standardPreferenceProvider.invoke() } returns preferenceProvider
        val biometricRepository = mockk<BiometricRepository>(relaxed = true)
        if (exception == null) {
            coEvery { biometricRepository.requestBiometrics(any()) } returns Unit
        } else {
            coEvery { biometricRepository.requestBiometrics(any()) } throws exception
        }
        return SecretAuthGate(
            biometricRepository = biometricRepository,
            standardPreferenceProvider = standardPreferenceProvider,
            encryptedPreferenceProvider = mockk<EncryptedPreferenceProvider>(relaxed = true),
        )
    }
}
