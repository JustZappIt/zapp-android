package co.electriccoin.zcash.ui.common.viewmodel

import android.app.Application
import androidx.biometric.BiometricManager
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.spackle.AndroidApiVersion
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.fixture.VersionInfoFixture
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for MOB-1447: opening the app before any wallet has been created or
 * restored must never surface the app-access authentication prompt.
 *
 * [WalletViewModel.secretState] starts at [SecretState.LOADING] and only resolves to
 * [SecretState.NONE] or [SecretState.READY] once preferences and configuration finish loading.
 * [AuthenticationViewModel.appAccessAuthenticationResultState] must defer to
 * [AuthenticationUIState.Initial] while that resolution is pending, instead of racing ahead to
 * [AuthenticationUIState.Required] and triggering a prompt that a moment later turns out to have
 * been unnecessary.
 *
 * Ported from zodl's AuthenticationViewModelTest (dd1c10960), adapted to our parameterized
 * [AuthenticationUIState.Required], which carries the configured [AuthMethod].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationViewModelTest {
    private val application = mockk<Application>(relaxed = true)
    private val biometricManager = mockk<BiometricManager>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkObject(AndroidApiVersion)
        every { AndroidApiVersion.isExactlyO } returns false
        every { AndroidApiVersion.isAtLeastR } returns true
        every { AndroidApiVersion.isExactlyP } returns false
        every { AndroidApiVersion.isExactlyQ } returns false
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    /**
     * The regression itself. Asserted on the state flow's settled value rather than the collected
     * sequence: [kotlinx.coroutines.flow.StateFlow] conflates, so a collector can miss a
     * transient [AuthenticationUIState.Required] that the value still holds — collecting alone
     * passes even against the pre-fix code.
     */
    @Test
    fun loadingSecretStateNeverAsksForAuth() =
        runTest {
            val (viewModel, _) = newViewModel(secretState = SecretState.LOADING)

            val states = mutableListOf<AuthenticationUIState>()
            backgroundScope.launch { viewModel.appAccessAuthenticationResultState.collect { states += it } }
            advanceUntilIdle()

            assertEquals(AuthenticationUIState.Initial, viewModel.appAccessAuthenticationResultState.value)
            assertTrue(
                states.none { it is AuthenticationUIState.Required },
                "Prompted for auth while the wallet state was still LOADING: $states"
            )
        }

    @Test
    fun loadingThenNoneBecomesNotRequired() =
        runTest {
            val (viewModel, secretState) = newViewModel(secretState = SecretState.LOADING)

            backgroundScope.launch { viewModel.appAccessAuthenticationResultState.collect { } }
            advanceUntilIdle()

            secretState.value = SecretState.NONE
            advanceUntilIdle()

            assertEquals(AuthenticationUIState.NotRequired, viewModel.appAccessAuthenticationResultState.value)
        }

    @Test
    fun loadingThenReadyBecomesRequired() =
        runTest {
            val (viewModel, secretState) = newViewModel(secretState = SecretState.LOADING)

            backgroundScope.launch { viewModel.appAccessAuthenticationResultState.collect { } }
            advanceUntilIdle()

            secretState.value = SecretState.READY
            advanceUntilIdle()

            assertEquals(
                AuthenticationUIState.Required(AuthMethod.BIOMETRIC),
                viewModel.appAccessAuthenticationResultState.value
            )
        }

    /**
     * The [AuthMethod] the prompt is asked for is the one currently persisted — our addition over
     * upstream, whose [AuthenticationUIState.Required] carries nothing.
     */
    @Test
    fun readyCarriesTheConfiguredAuthMethod() =
        runTest {
            val (viewModel, _) =
                newViewModel(secretState = SecretState.READY, authMethod = AuthMethod.PIN)

            backgroundScope.launch { viewModel.appAccessAuthenticationResultState.collect { } }
            advanceUntilIdle()

            assertEquals(
                AuthenticationUIState.Required(AuthMethod.PIN),
                viewModel.appAccessAuthenticationResultState.value
            )
        }

    @Test
    fun notRequiredSurvivesLaterWalletCreationInTheSameSession() =
        runTest {
            val (viewModel, secretState) = newViewModel(secretState = SecretState.NONE)

            backgroundScope.launch { viewModel.appAccessAuthenticationResultState.collect { } }
            advanceUntilIdle()
            assertEquals(AuthenticationUIState.NotRequired, viewModel.appAccessAuthenticationResultState.value)

            secretState.value = SecretState.READY
            advanceUntilIdle()

            assertEquals(AuthenticationUIState.NotRequired, viewModel.appAccessAuthenticationResultState.value)
        }

    @Test
    fun disabledAuthenticationPreferenceSkipsPromptEvenWhenReady() =
        runTest {
            val (viewModel, _) =
                newViewModel(secretState = SecretState.READY, isAppAccessAuthentication = false)

            backgroundScope.launch { viewModel.appAccessAuthenticationResultState.collect { } }
            advanceUntilIdle()

            assertEquals(AuthenticationUIState.NotRequired, viewModel.appAccessAuthenticationResultState.value)
        }

    @Test
    fun runningUnderTestServiceSkipsPromptEvenWhenReady() =
        runTest {
            val (viewModel, _) =
                newViewModel(secretState = SecretState.READY, isRunningUnderTestService = true)

            backgroundScope.launch { viewModel.appAccessAuthenticationResultState.collect { } }
            advanceUntilIdle()

            assertEquals(AuthenticationUIState.NotRequired, viewModel.appAccessAuthenticationResultState.value)
        }

    private fun newViewModel(
        secretState: SecretState,
        isAppAccessAuthentication: Boolean? = null,
        authMethod: AuthMethod = AuthMethod.BIOMETRIC,
        isRunningUnderTestService: Boolean = false
    ): Pair<AuthenticationViewModel, MutableStateFlow<SecretState>> {
        val secretStateFlow = MutableStateFlow(secretState)
        val walletViewModel = mockk<WalletViewModel>()
        every { walletViewModel.secretState } returns secretStateFlow

        val preferences =
            FakePreferenceProvider(
                buildMap {
                    isAppAccessAuthentication?.let {
                        put(StandardPreferenceKeys.IS_APP_ACCESS_AUTHENTICATION.key.key, it.toString())
                    }
                    put(StandardPreferenceKeys.AUTH_METHOD.key.key, authMethod.persistedValue)
                }
            )
        val standardPreferenceProvider = mockk<StandardPreferenceProvider>()
        coEvery { standardPreferenceProvider() } returns preferences

        val getVersionInfo = mockk<GetVersionInfoProvider>()
        every { getVersionInfo() } returns
            VersionInfoFixture.new(isRunningUnderTestService = isRunningUnderTestService)

        val viewModel =
            AuthenticationViewModel(
                application = application,
                biometricManager = biometricManager,
                getVersionInfo = getVersionInfo,
                standardPreferenceProvider = standardPreferenceProvider,
                encryptedPreferenceProvider = mockk<EncryptedPreferenceProvider>(relaxed = true),
                walletViewModel = walletViewModel
            )

        return viewModel to secretStateFlow
    }
}

/**
 * A hand-written [PreferenceProvider] stand-in, not a MockK proxy: [PreferenceProvider]'s methods
 * take the [PreferenceKey] value class, and MockK's reflection-based call recorder throws on that
 * combination for suspend members (`getString`).
 *
 * Keyed by preference name rather than upstream's single value, because our
 * [AuthenticationViewModel] reads two string-backed preferences off the same provider — the
 * app-access boolean and the auth method — and one shared value cannot satisfy both. A key absent
 * from [values] reads as unset, so the [co.electriccoin.zcash.ui.preference.StandardPreferenceKeys]
 * default applies. [observe] emits once, which is all
 * [co.electriccoin.zcash.preference.model.entry.PreferenceDefault.observe] needs to trigger its
 * read.
 */
private class FakePreferenceProvider(
    private val values: Map<String, String?>
) : PreferenceProvider {
    override suspend fun hasKey(key: PreferenceKey) = values.containsKey(key.key)

    override suspend fun putString(
        key: PreferenceKey,
        value: String?
    ) = Unit

    override suspend fun putStringSet(
        key: PreferenceKey,
        value: Set<String>?
    ) = Unit

    override suspend fun putLong(
        key: PreferenceKey,
        value: Long?
    ) = Unit

    override suspend fun getLong(key: PreferenceKey): Long? = null

    override suspend fun getString(key: PreferenceKey): String? = values[key.key]

    override suspend fun getStringSet(key: PreferenceKey): Set<String>? = null

    override fun observe(key: PreferenceKey): Flow<String?> = flowOf(values[key.key])

    override suspend fun remove(key: PreferenceKey) = Unit

    override suspend fun clearPreferences(): Boolean = true
}
