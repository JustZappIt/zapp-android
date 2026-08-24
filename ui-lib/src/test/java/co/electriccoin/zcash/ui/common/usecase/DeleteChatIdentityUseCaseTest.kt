// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProvider
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Deleting a chat identity clears both preference stores, and `gift_cards_v1` lives in the
 * encrypted one. An unshared card's ephemeral seed is random rather than derived from the wallet
 * seed, so the dialog's promise that the seed phrase restores everything does not cover it.
 */
class DeleteChatIdentityUseCaseTest {
    @Test
    fun `refuses while an unshared funded gift card exists`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = true)

            assertFailsWith<UnsharedGiftFundsException> { fixture.useCase() }

            fixture.assertNothingWasDestroyed()
        }

    @Test
    fun `refuses when the gift card store cannot be read`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = null)

            assertFailsWith<UnsharedGiftFundsException> { fixture.useCase() }

            fixture.assertNothingWasDestroyed()
        }

    @Test
    fun `deletes once every gift card link has been handed out`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = false)

            fixture.useCase()

            coVerify(exactly = 1) { fixture.encryptedPreferences.clearPreferences() }
            coVerify(exactly = 1) { fixture.standardPreferences.clearPreferences() }
        }

    private class Fixture(
        hasUnsharedFunds: Boolean?,
    ) {
        val encryptedPreferences = mockk<PreferenceProvider>(relaxed = true)
        val standardPreferences = mockk<PreferenceProvider>(relaxed = true)
        val walletCoordinator = mockk<WalletCoordinator>(relaxed = true)
        val addressBookRepository = mockk<AddressBookRepository>(relaxed = true)
        val metadataRepository = mockk<MetadataRepository>(relaxed = true)
        val sdk = mockk<ZappMessagingSDK>(relaxed = true)

        private val giftCardStorageProvider = mockk<GiftCardStorageProvider>()
        private val synchronizerProvider = mockk<SynchronizerProvider>()
        private val standardPreferenceProvider = mockk<StandardPreferenceProvider>()
        private val encryptedPreferenceProvider = mockk<EncryptedPreferenceProvider>()

        val useCase: DeleteChatIdentityUseCase

        init {
            if (hasUnsharedFunds == null) {
                coEvery { giftCardStorageProvider.hasUnsharedFunds() } throws IllegalStateException("undecodable")
            } else {
                coEvery { giftCardStorageProvider.hasUnsharedFunds() } returns hasUnsharedFunds
            }
            every { walletCoordinator.deleteSdkDataFlow() } returns flowOf(true)
            every { synchronizerProvider.synchronizer } returns MutableStateFlow(null)
            coEvery { standardPreferenceProvider() } returns standardPreferences
            coEvery { encryptedPreferenceProvider() } returns encryptedPreferences

            useCase =
                DeleteChatIdentityUseCase(
                    sdk = sdk,
                    walletCoordinator = walletCoordinator,
                    synchronizerProvider = synchronizerProvider,
                    standardPreferenceProvider = standardPreferenceProvider,
                    encryptedPreferenceProvider = encryptedPreferenceProvider,
                    flexaRepository = mockk<FlexaRepository>(relaxed = true),
                    addressBookRepository = addressBookRepository,
                    metadataRepository = metadataRepository,
                    homeMessageCacheRepository = mockk<HomeMessageCacheRepository>(relaxed = true),
                    chatBlockedKeysStorageProvider = mockk<ChatBlockedKeysStorageProvider>(relaxed = true),
                    ensureNoUnsharedGiftFunds =
                        EnsureNoUnsharedGiftFundsUseCase(
                            giftCardStorageProvider,
                            mockk(relaxed = true),
                        ),
                )
        }

        /** The refusal has to land before the messaging shutdown, not merely before the last delete. */
        fun assertNothingWasDestroyed() {
            coVerify(exactly = 0) { encryptedPreferences.clearPreferences() }
            coVerify(exactly = 0) { standardPreferences.clearPreferences() }
            coVerify(exactly = 0) { walletCoordinator.deleteSdkDataFlow() }
            coVerify(exactly = 0) { addressBookRepository.delete() }
            coVerify(exactly = 0) { metadataRepository.delete() }
            coVerify(exactly = 0) { sdk.shutdown() }
        }
    }
}
