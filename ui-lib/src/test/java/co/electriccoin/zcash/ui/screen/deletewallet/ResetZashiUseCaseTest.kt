// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.deletewallet

import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProvider
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.BaseBalanceRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.common.usecase.EnsureNoUnsharedGiftFundsUseCase
import co.electriccoin.zcash.ui.common.usecase.UnsharedGiftFundsException
import co.electriccoin.zcash.ui.screen.tabs.SelectedTabRepository
import co.electriccoin.zcash.ui.screen.tabs.view.ZappTab
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A wipe must not be able to destroy the only key material that can reach a gift card's funds.
 * The ephemeral seed is random rather than derived from the wallet seed and there is no reclaim,
 * so `gift_cards_v1` — inside the encrypted preferences this use case clears — is the only copy.
 */
class ResetZashiUseCaseTest {
    @Test
    fun `refuses to reset while an unshared funded gift card exists`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = true)

            assertFailsWith<UnsharedGiftFundsException> { fixture.useCase(keepFiles = true) }

            fixture.assertNothingWasDestroyed()
        }

    @Test
    fun `refuses to reset when the gift card store cannot be read`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = null)

            assertFailsWith<UnsharedGiftFundsException> { fixture.useCase(keepFiles = true) }

            fixture.assertNothingWasDestroyed()
        }

    @Test
    fun `resets once every gift card link has been handed out`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = false)

            fixture.useCase(keepFiles = true)

            coVerify(exactly = 1) { fixture.encryptedPreferences.clearPreferences() }
            coVerify(exactly = 1) { fixture.biometricRepository.requestBiometrics(any()) }
        }

    @Test
    fun `conversions and private USD's engine stop before anything of the wallet is wiped`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = false)

            fixture.useCase(keepFiles = true)

            coVerifyOrder {
                fixture.atomicSwapRepository.reset()
                fixture.reverseSwapRepository.reset()
                fixture.railgunWalletRepository.reset()
                fixture.encryptedPreferences.clearPreferences()
            }
        }

    @Test
    fun `the next wallet opens on the default tab, not the one this one was reset from`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = false)
            fixture.selectedTabRepository.select(ZappTab.YOU)

            fixture.useCase(keepFiles = true)

            assertEquals(ZappTab.DEFAULT, fixture.selectedTabRepository.requested.value)
        }

    @Test
    fun `explicit start fresh confirmation may discard gift recovery`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = true)

            fixture.useCase(keepFiles = true, allowGiftDataLoss = true)

            coVerify(exactly = 1) { fixture.encryptedPreferences.clearPreferences() }
        }

    private class Fixture(
        hasUnsharedFunds: Boolean?,
    ) {
        val encryptedPreferences = mockk<PreferenceProvider>(relaxed = true)
        val standardPreferences = mockk<PreferenceProvider>(relaxed = true)
        val biometricRepository = mockk<BiometricRepository>(relaxed = true)
        val walletCoordinator = mockk<WalletCoordinator>(relaxed = true)
        val addressBookRepository = mockk<AddressBookRepository>(relaxed = true)
        val metadataRepository = mockk<MetadataRepository>(relaxed = true)
        val atomicSwapRepository = mockk<AtomicSwapRepository>(relaxed = true)
        val reverseSwapRepository = mockk<ReverseSwapRepository>(relaxed = true)
        val railgunWalletRepository = mockk<RailgunWalletRepository>(relaxed = true)
        val selectedTabRepository = SelectedTabRepository()

        val giftCardStorageProvider = mockk<GiftCardStorageProvider>()
        private val synchronizerProvider = mockk<SynchronizerProvider>()
        private val standardPreferenceProvider = mockk<StandardPreferenceProvider>()
        private val encryptedPreferenceProvider = mockk<EncryptedPreferenceProvider>()

        val useCase: ResetZashiUseCase

        init {
            if (hasUnsharedFunds == null) {
                coEvery { giftCardStorageProvider.hasUnsharedFunds() } throws IllegalStateException("undecodable")
            } else {
                coEvery { giftCardStorageProvider.hasUnsharedFunds() } returns hasUnsharedFunds
            }
            every { walletCoordinator.deleteSdkDataFlow() } returns flowOf(true)
            coEvery { synchronizerProvider.getSynchronizer() } returns mockk<CloseableSynchronizer>(relaxed = true)
            coEvery { standardPreferenceProvider() } returns standardPreferences
            coEvery { encryptedPreferenceProvider() } returns encryptedPreferences

            useCase =
                ResetZashiUseCase(
                    walletCoordinator = walletCoordinator,
                    flexaRepository = mockk<FlexaRepository>(relaxed = true),
                    synchronizerProvider = synchronizerProvider,
                    standardPreferenceProvider = standardPreferenceProvider,
                    encryptedPreferenceProvider = encryptedPreferenceProvider,
                    homeMessageCacheRepository = mockk<HomeMessageCacheRepository>(relaxed = true),
                    biometricRepository = biometricRepository,
                    addressBookRepository = addressBookRepository,
                    metadataRepository = metadataRepository,
                    chatBlockedKeysStorageProvider = mockk<ChatBlockedKeysStorageProvider>(relaxed = true),
                    peerCashOutRepository = mockk<PeerCashOutRepository>(relaxed = true),
                    baseBalanceRepository = mockk<BaseBalanceRepository>(relaxed = true),
                    migrationAppHooks = mockk<MigrationAppHooks>(relaxed = true),
                    ensureNoUnsharedGiftFunds =
                        EnsureNoUnsharedGiftFundsUseCase(
                            giftCardStorageProvider,
                            mockk(relaxed = true),
                        ),
                    atomicSwapRepository = atomicSwapRepository,
                    reverseSwapRepository = reverseSwapRepository,
                    railgunWalletRepository = railgunWalletRepository,
                    privateUsdSenders = mockk(relaxed = true),
                    selectedTabRepository = selectedTabRepository,
                )
        }

        /** The refusal has to land before the prompt, not merely before the last delete. */
        fun assertNothingWasDestroyed() {
            coVerify(exactly = 0) { encryptedPreferences.clearPreferences() }
            coVerify(exactly = 0) { standardPreferences.clearPreferences() }
            coVerify(exactly = 0) { walletCoordinator.deleteSdkDataFlow() }
            coVerify(exactly = 0) { biometricRepository.requestBiometrics(any()) }
            coVerify(exactly = 0) { addressBookRepository.delete() }
            coVerify(exactly = 0) { metadataRepository.delete() }
            coVerify(exactly = 0) { atomicSwapRepository.reset() }
            coVerify(exactly = 0) { reverseSwapRepository.reset() }
            coVerify(exactly = 0) { railgunWalletRepository.reset() }
        }
    }
}
