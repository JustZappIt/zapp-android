package co.electriccoin.zcash.ui.screen.deletewallet

import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositsRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSenders
import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.BaseBalanceRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.common.usecase.EnsureNoUnfinishedConversionsUseCase
import co.electriccoin.zcash.ui.common.usecase.EnsureNoUnsharedGiftFundsUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.tabs.SelectedTabRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.internal.closeQuietly
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one destructive sequence a wallet leaves this device by: Reset Zapp and the chat's Delete identity both run
 * it.
 *
 * It refuses before touching anything while a gift card's link never left the device or a Shielded USD conversion
 * is under way, unless the caller passes the user's explicit consent to losing them; then it asks for biometrics,
 * and only then starts the tail. From its first irreversible step the tail cannot be cancelled, and every step that
 * waits on a loop, on the engine or on the SDK is bounded: the preferences holding the seed are cleared whatever
 * came before, because the seed is what matters.
 */
class ResetZashiUseCase internal constructor(
    private val walletCoordinator: WalletCoordinator,
    private val flexaRepository: FlexaRepository,
    private val synchronizerProvider: SynchronizerProvider,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val homeMessageCacheRepository: HomeMessageCacheRepository,
    private val biometricRepository: BiometricRepository,
    private val addressBookRepository: AddressBookRepository,
    private val metadataRepository: MetadataRepository,
    private val chatBlockedKeysStorageProvider: ChatBlockedKeysStorageProvider,
    private val peerCashOutRepository: PeerCashOutRepository,
    private val baseBalanceRepository: BaseBalanceRepository,
    private val migrationAppHooks: MigrationAppHooks,
    private val ensureNoUnsharedGiftFunds: EnsureNoUnsharedGiftFundsUseCase,
    private val ensureNoUnfinishedConversions: EnsureNoUnfinishedConversionsUseCase,
    private val atomicSwapRepository: AtomicSwapRepository,
    private val reverseSwapRepository: ReverseSwapRepository,
    private val railgunWalletRepository: RailgunWalletRepository,
    private val privateUsdSenders: PrivateUsdSenders,
    private val privateUsdDeposits: PrivateUsdDepositsRepository,
    private val selectedTabRepository: SelectedTabRepository,
) {
    /**
     * @param beforeWipe A caller's own teardown, run once the user has authenticated and before the first
     * irreversible step, so a cancelled prompt leaves it undone.
     * @throws co.electriccoin.zcash.ui.common.usecase.UnsharedGiftFundsException before anything is touched,
     * unless [allowGiftDataLoss].
     * @throws co.electriccoin.zcash.ui.common.usecase.UnfinishedConversionsException before anything is touched,
     * unless [allowConversionLoss].
     */
    suspend operator fun invoke(
        keepFiles: Boolean,
        allowGiftDataLoss: Boolean = false,
        allowConversionLoss: Boolean = false,
        beforeWipe: suspend () -> Unit = {},
    ): ResetZashiResult {
        // clearSharedPrefs() wipes gift_cards_v1, and an unshared card's ephemeral seed lives nowhere else; the
        // records carrying a conversion to its end live there too. Refuse before anything is touched.
        if (!allowGiftDataLoss) ensureNoUnsharedGiftFunds()
        if (!allowConversionLoss) ensureNoUnfinishedConversions()
        if (!requestBiometrics()) return ResetZashiResult.NOT_AUTHENTICATED
        // A scope cancelled from here on (the screen left, the process trimmed) must not leave half a wallet.
        withContext(NonCancellable) {
            beforeWipe()
            wipe(keepFiles)
        }
        return ResetZashiResult.WIPED
    }

    /** True once the user passed the prompt; false when they cancelled it or it failed, which wipes nothing. */
    private suspend fun requestBiometrics(): Boolean =
        try {
            biometricRepository.requestBiometrics(
                BiometricRequest(
                    message =
                        stringRes(
                            R.string.authentication_system_ui_subtitle,
                            stringRes(R.string.authentication_use_case_delete_wallet)
                        )
                )
            )
            true
        } catch (_: BiometricsFailureException) {
            false
        } catch (_: BiometricsCancelledException) {
            false
        }

    private suspend fun wipe(keepFiles: Boolean) {
        // Migration workers are self-rechaining OneTimeWork — a wallet wipe that leaves them
        // scheduled produces zombie retries against a wallet that no longer exists (Milan,
        // 2026-07-30). Cancel while the accounts are still resolvable.
        migrationAppHooks.cancelMigrationWork()
        flexaRepository.disconnect()
        // App-scoped and wallet-specific. Joining before anything is wiped is the point: an
        // attempt still running would write its checkpoint back into storage this is about to
        // clear, and would keep driving the deleted wallet's smart account.
        bounded("the cash-out attempt") { peerCashOutRepository.reset() }
        baseBalanceRepository.reset()
        // Conversions too: a loop still running would write the wiped wallet's swap back.
        bounded("the conversion into USD") { atomicSwapRepository.reset() }
        bounded("the conversion into ZEC") { reverseSwapRepository.reset() }
        // Private USD's sends, its deposits, its engine, and the page storage holding the wallet it opened. A
        // deposit pass still running would write the wiped wallet's deposits back.
        bounded("Private USD's sends") { privateUsdSenders.current?.reset() }
        bounded("Private USD's deposits") { privateUsdDeposits.reset() }
        bounded("the Railgun engine") { railgunWalletRepository.reset() }
        deleteLocalFiles(keepFiles)
        closeSynchronizer()
        clearSDK()
        clearSharedPrefs()
        clearInMemoryData()
    }

    /**
     * Runs [step], and stops waiting on it after [timeout], or when it fails, with a log: nothing on the way to the
     * preferences may hold up the seed's wipe. A loop [step] told to stop stays told; only the wait is given up.
     *
     * Only for the tail under [NonCancellable]: a cancellation that reaches here is a step's own, such as a timeout
     * inside it, never this scope's, so it is a failure of the step like any other.
     */
    private suspend fun bounded(
        what: String,
        timeout: Duration = STEP_TIMEOUT,
        loud: Boolean = false,
        step: suspend () -> Unit,
    ) {
        val outcome = runCatching { withTimeoutOrNull(timeout) { step() } }
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            Twig.error(failure) { "Reset: $what failed; wiping the rest regardless" }
        } else if (outcome.getOrNull() == null) {
            val message = { "Reset: $what did not finish within $timeout; wiping the rest regardless" }
            if (loud) Twig.error(message) else Twig.warn(message)
        }
    }

    /**
     * Waits for a synchronizer to close, bounded by [CLOSE_SYNCHRONIZER_TIMEOUT]. A latched seed
     * mismatch ([WalletCoordinator.isSeedMismatch]) keeps [SynchronizerProvider.getSynchronizer]'s
     * underlying flow null forever, which would otherwise hang this step indefinitely after
     * [deleteLocalFiles] already ran. When no synchronizer materializes in time, this is a no-op —
     * [clearSDK]'s lockout-aware erase still handles teardown of any lingering SDK state.
     */
    private suspend fun closeSynchronizer() {
        withTimeoutOrNull(CLOSE_SYNCHRONIZER_TIMEOUT) { synchronizerProvider.getSynchronizer() }
            ?.let { (it as CloseableSynchronizer).closeQuietly() }
    }

    private fun deleteLocalFiles(keepFiles: Boolean) {
        if (!keepFiles) {
            addressBookRepository.delete()
            metadataRepository.delete()
        }
    }

    /** The SDK's delete can wait on a database still closing; the preferences are cleared even if it never answers. */
    private suspend fun clearSDK() {
        bounded("the SDK's data delete", CLEAR_SDK_TIMEOUT, loud = true) {
            walletCoordinator.deleteSdkDataFlow().first()
        }
    }

    private suspend fun clearSharedPrefs() {
        standardPreferenceProvider().clearPreferences()
        encryptedPreferenceProvider().clearPreferences()
        // Outside the provider stack above; without this the deleted wallet's blocklist mirror
        // survives into the next wallet on this device.
        chatBlockedKeysStorageProvider.clear()
    }

    private fun clearInMemoryData() {
        homeMessageCacheRepository.reset()
        // The next wallet opens where a new one does, not on the tab this one was reset from.
        selectedTabRepository.reset()
    }

    companion object {
        private val CLOSE_SYNCHRONIZER_TIMEOUT = 5.seconds

        /** Long enough for a loop mid-request to notice its cancellation, short enough to keep a wipe from hanging. */
        private val STEP_TIMEOUT = 10.seconds

        /** The SDK deletes its databases and cache after the synchronizer let go of them. */
        private val CLEAR_SDK_TIMEOUT = 30.seconds
    }
}
