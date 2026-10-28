// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositsRepository
import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProvider
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.BaseBalanceRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiResult
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiUseCase
import co.electriccoin.zcash.ui.screen.tabs.SelectedTabRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Deleting a chat identity is the wallet's one destructive sequence with the messaging worklet stopped first: it
 * refuses what that sequence refuses, asks for the same prompt, and stops the worklet only once the prompt passed.
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
    fun `refuses while a conversion is under way`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = false, hasUnfinishedConversions = true)

            assertFailsWith<UnfinishedConversionsException> { fixture.useCase() }

            fixture.assertNothingWasDestroyed()
        }

    @Test
    fun `explicit confirmation may lose a conversion under way`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = false, hasUnfinishedConversions = true)

            assertEquals(ResetZashiResult.WIPED, fixture.useCase(allowConversionLoss = true))

            coVerify(exactly = 1) { fixture.encryptedPreferences.clearPreferences() }
        }

    @Test
    fun `a cancelled prompt leaves the chat running and wipes nothing`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = false, isAuthenticated = false)

            assertEquals(ResetZashiResult.NOT_AUTHENTICATED, fixture.useCase())

            coVerify(exactly = 1) { fixture.biometricRepository.requestBiometrics(any()) }
            coVerify(exactly = 0) { fixture.sdk.shutdown() }
            coVerify(exactly = 0) { fixture.encryptedPreferences.clearPreferences() }
            coVerify(exactly = 0) { fixture.standardPreferences.clearPreferences() }
        }

    @Test
    fun `deletes once every gift card link has been handed out, the worklet stopped after the prompt`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = false)

            assertEquals(ResetZashiResult.WIPED, fixture.useCase())

            coVerifyOrder {
                fixture.biometricRepository.requestBiometrics(any())
                fixture.sdk.shutdown()
                fixture.atomicSwapRepository.reset()
                fixture.railgunWalletRepository.reset()
                fixture.addressBookRepository.delete()
                fixture.encryptedPreferences.clearPreferences()
            }
            coVerify(exactly = 1) { fixture.standardPreferences.clearPreferences() }
            coVerify(exactly = 1) { fixture.metadataRepository.delete() }
        }

    @Test
    fun `a worklet that fails to stop does not keep the wallet on the device`() =
        runTest {
            val fixture = Fixture(hasUnsharedFunds = false)
            every { fixture.sdk.shutdown() } throws IllegalStateException("bridge gone")

            assertEquals(ResetZashiResult.WIPED, fixture.useCase())

            coVerify(exactly = 1) { fixture.encryptedPreferences.clearPreferences() }
        }

    private class Fixture(
        hasUnsharedFunds: Boolean?,
        hasUnfinishedConversions: Boolean = false,
        isAuthenticated: Boolean = true,
    ) {
        val encryptedPreferences = mockk<PreferenceProvider>(relaxed = true)
        val standardPreferences = mockk<PreferenceProvider>(relaxed = true)
        val biometricRepository = mockk<BiometricRepository>(relaxed = true)
        val walletCoordinator = mockk<WalletCoordinator>(relaxed = true)
        val addressBookRepository = mockk<AddressBookRepository>(relaxed = true)
        val metadataRepository = mockk<MetadataRepository>(relaxed = true)
        val atomicSwapRepository = mockk<AtomicSwapRepository>(relaxed = true)
        val railgunWalletRepository = mockk<RailgunWalletRepository>(relaxed = true)
        val sdk = mockk<ZappMessagingSDK>(relaxed = true)

        private val giftCardStorageProvider = mockk<GiftCardStorageProvider>()
        private val ensureNoUnfinishedConversions = mockk<EnsureNoUnfinishedConversionsUseCase>()
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
            if (hasUnfinishedConversions) {
                coEvery { ensureNoUnfinishedConversions() } throws UnfinishedConversionsException(count = 1)
            } else {
                coEvery { ensureNoUnfinishedConversions() } just Runs
            }
            if (!isAuthenticated) {
                coEvery { biometricRepository.requestBiometrics(any()) } throws BiometricsCancelledException()
            }
            every { walletCoordinator.deleteSdkDataFlow() } returns flowOf(true)
            coEvery { synchronizerProvider.getSynchronizer() } returns mockk<CloseableSynchronizer>(relaxed = true)
            coEvery { standardPreferenceProvider() } returns standardPreferences
            coEvery { encryptedPreferenceProvider() } returns encryptedPreferences

            val resetZashi =
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
                    ensureNoUnfinishedConversions = ensureNoUnfinishedConversions,
                    atomicSwapRepository = atomicSwapRepository,
                    reverseSwapRepository = mockk<ReverseSwapRepository>(relaxed = true),
                    railgunWalletRepository = railgunWalletRepository,
                    privateUsdSenders = mockk(relaxed = true),
                    privateUsdDeposits = mockk<PrivateUsdDepositsRepository>(relaxed = true),
                    selectedTabRepository = SelectedTabRepository(),
                )
            useCase = DeleteChatIdentityUseCase(sdk = sdk, resetZashi = resetZashi)
        }

        /** The refusal has to land before the prompt and the messaging shutdown, not merely before the last delete. */
        fun assertNothingWasDestroyed() {
            coVerify(exactly = 0) { biometricRepository.requestBiometrics(any()) }
            coVerify(exactly = 0) { sdk.shutdown() }
            coVerify(exactly = 0) { encryptedPreferences.clearPreferences() }
            coVerify(exactly = 0) { standardPreferences.clearPreferences() }
            coVerify(exactly = 0) { walletCoordinator.deleteSdkDataFlow() }
            coVerify(exactly = 0) { addressBookRepository.delete() }
            coVerify(exactly = 0) { metadataRepository.delete() }
            coVerify(exactly = 0) { atomicSwapRepository.reset() }
            coVerify(exactly = 0) { railgunWalletRepository.reset() }
        }
    }
}
