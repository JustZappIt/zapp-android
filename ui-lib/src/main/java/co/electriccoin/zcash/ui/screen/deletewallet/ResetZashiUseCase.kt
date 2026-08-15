package co.electriccoin.zcash.ui.screen.deletewallet

import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
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
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.first
import okhttp3.internal.closeQuietly

class ResetZashiUseCase(
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
) {
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend operator fun invoke(keepFiles: Boolean) {
        try {
            requestBiometrics()
            // Migration workers are self-rechaining OneTimeWork — a wallet wipe that leaves them
            // scheduled produces zombie retries against a wallet that no longer exists (Milan,
            // 2026-07-30). Cancel while the accounts are still resolvable.
            migrationAppHooks.cancelMigrationWork()
            flexaRepository.disconnect()
            // App-scoped and wallet-specific. Joining before anything is wiped is the point: an
            // attempt still running would write its checkpoint back into storage this is about to
            // clear, and would keep driving the deleted wallet's smart account.
            peerCashOutRepository.reset()
            baseBalanceRepository.reset()
            deleteLocalFiles(keepFiles)
            closeSynchronizer()
            clearSDK()
            clearSharedPrefs()
            clearInMemoryData()
        } catch (_: BiometricsFailureException) {
            // do nothing
        } catch (_: BiometricsCancelledException) {
            // do nothing
        }
    }

    private suspend fun requestBiometrics() {
        biometricRepository.requestBiometrics(
            BiometricRequest(
                message =
                    stringRes(
                        R.string.authentication_system_ui_subtitle,
                        stringRes(R.string.authentication_use_case_delete_wallet)
                    )
            )
        )
    }

    private suspend fun closeSynchronizer() {
        (synchronizerProvider.getSynchronizer() as CloseableSynchronizer).closeQuietly()
    }

    private fun deleteLocalFiles(keepFiles: Boolean) {
        if (!keepFiles) {
            addressBookRepository.delete()
            metadataRepository.delete()
        }
    }

    private suspend fun clearSDK() {
        walletCoordinator.deleteSdkDataFlow().first()
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
    }
}
