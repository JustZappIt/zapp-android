package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.screen.chat.common.runChatCall
import kotlinx.coroutines.flow.first
import xyz.justzappit.zappmessaging.ZappMessagingSDK

class DeleteChatIdentityUseCase(
    private val sdk: ZappMessagingSDK,
    private val walletCoordinator: WalletCoordinator,
    private val synchronizerProvider: SynchronizerProvider,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val flexaRepository: FlexaRepository,
    private val addressBookRepository: AddressBookRepository,
    private val metadataRepository: MetadataRepository,
    private val homeMessageCacheRepository: HomeMessageCacheRepository,
    private val chatBlockedKeysStorageProvider: ChatBlockedKeysStorageProvider,
    private val ensureNoUnsharedGiftFunds: EnsureNoUnsharedGiftFundsUseCase,
) {
    /** @throws UnsharedGiftFundsException before anything is deleted. */
    suspend operator fun invoke() {
        // This clears both preference stores below, and gift_cards_v1 lives in the encrypted one.
        // Refuse before anything is touched, exactly as ResetZashiUseCase does.
        ensureNoUnsharedGiftFunds()

        runChatCall("DeleteChatIdentityUseCase: shutdown failed") {
            sdk.shutdown()
        }

        runCatching { flexaRepository.disconnect() }
            .onFailure { Twig.warn(it) { "DeleteChatIdentityUseCase: flexa disconnect failed" } }

        addressBookRepository.delete()
        metadataRepository.delete()

        runCatching {
            (synchronizerProvider.synchronizer.value as? CloseableSynchronizer)?.close()
        }.onFailure { Twig.warn(it) { "DeleteChatIdentityUseCase: synchronizer close failed" } }

        runCatching { walletCoordinator.deleteSdkDataFlow().first() }
            .onFailure { Twig.warn(it) { "DeleteChatIdentityUseCase: SDK data delete failed" } }

        standardPreferenceProvider().clearPreferences()
        encryptedPreferenceProvider().clearPreferences()
        chatBlockedKeysStorageProvider.clear()

        homeMessageCacheRepository.reset()
    }
}
