package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import co.electriccoin.zcash.ui.util.loggableNot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Disconnecting this account would orphan an in-progress gift claim destination. */
class GiftClaimDestinationAccountInUseException : RuntimeException("Account is receiving a gift claim")

class DisconnectUseCase(
    private val accountDataSource: AccountDataSource,
    private val biometricRepository: BiometricRepository,
    private val migrationAppHooks: MigrationAppHooks,
    private val receivedGiftStorageProvider: ReceivedGiftStorageProvider,
) {
    private val logger = loggableNot("DisconnectUseCase")

    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(keystoneAccount: KeystoneAccount) =
        withContext(Dispatchers.IO) {
            ensureNoGiftClaimUses(keystoneAccount)
            biometricRepository.requestBiometrics(
                BiometricRequest(message = stringRes(R.string.disconnect_hardware_wallet_biometric_message))
            )

            // Authentication can leave this coroutine suspended while a claim starts elsewhere.
            // Re-check immediately before deleting the destination account.
            ensureNoGiftClaimUses(keystoneAccount)

            // A disconnected Keystone account must take its scheduled migration work with it —
            // otherwise the lanes zombie-retry for an account that no longer exists.
            migrationAppHooks.cancelMigrationWork(keystoneAccount.sdkAccount.accountUuid.toStorageKeyId())

            logger("deleteAccount $keystoneAccount")
            // Delete the hardware wallet account
            accountDataSource.deleteAccount(keystoneAccount)

            logger("deleteAccount success")

            // Explicitly select Zashi account after disconnecting Keystone
            val zashiAccount = accountDataSource.getZashiAccount()
            accountDataSource.selectAccount(zashiAccount)
        }

    /**
     * Refuses while a claim this wallet actually broadcast is still pointed at [keystoneAccount].
     *
     * Scoped to [ReceivedGift.isUnsettledClaim] like every other consumer (`docs/gift-cards.md`
     * §6.3). A receipt is written before the claim scan, so an unscoped reading also refuses over a
     * card this wallet merely looked at — and this path, unlike the wallet reset, offers no way to
     * proceed anyway, so such a receipt would make the account undisconnectable for good.
     */
    private suspend fun ensureNoGiftClaimUses(keystoneAccount: KeystoneAccount) {
        val accountId = keystoneAccount.sdkAccount.accountUuid.toStorageKeyId()
        if (
            receivedGiftStorageProvider
                .getAll()
                .any { it.isUnsettledClaim && it.destinationAccountUuid == accountId }
        ) {
            throw GiftClaimDestinationAccountInUseException()
        }
    }

    suspend fun getKeystoneAccount(): KeystoneAccount? =
        accountDataSource
            .getAllAccounts()
            .filterIsInstance<KeystoneAccount>()
            .firstOrNull()
}
