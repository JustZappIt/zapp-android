// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.GiftKeyProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.GiftMessage
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/** Why a card could not be minted. Each case is a distinct thing to tell the sender. */
enum class GiftCardCreationError {
    INVALID_AMOUNT,

    MESSAGE_TOO_LONG,

    /** Funding a card needs a spending key this device holds. */
    KEYSTONE_ACCOUNT_UNSUPPORTED,

    /** Gift cards exist on mainnet and testnet only. */
    UNSUPPORTED_NETWORK,

    /** No chain tip yet, so there is no honest birthday to stamp on the card. */
    CHAIN_TIP_UNAVAILABLE,

    /** The record did not read back as written. Refuse to fund what we cannot recover. */
    PERSIST_FAILED,
}

class GiftCardCreationException(
    val error: GiftCardCreationError,
) : RuntimeException(error.name)

/**
 * Mints a gift card and persists it, without touching the network.
 *
 * The resulting card is a [GiftCardStatus.DRAFT]: key material exists and is on disk, nothing has
 * been funded. Funding is a separate step *precisely because* of the ordering — the record has to
 * be durable before any money moves, since a crash between submitting the funding transaction and
 * writing the record loses the ephemeral seed, and with it the funds, permanently. There is no
 * reclaim.
 */
class CreateGiftCardUseCase(
    private val accountDataSource: AccountDataSource,
    private val synchronizerProvider: SynchronizerProvider,
    private val giftKeyProvider: GiftKeyProvider,
    private val giftCardStorageProvider: GiftCardStorageProvider,
) {
    suspend operator fun invoke(
        amount: Zatoshi,
        message: String? = null,
        expiresAt: Instant? = null,
        sourceAccount: WalletAccount? = null,
    ): StoredGiftCard {
        ensure(amount.value > 0, GiftCardCreationError.INVALID_AMOUNT)

        val note = message?.trim()?.takeIf { it.isNotEmpty() }
        ensure(note == null || GiftMessage.isWithinLimits(note), GiftCardCreationError.MESSAGE_TOO_LONG)

        // Keystone holds the spending key on the device, so funding one is a different flow. Out
        // of scope for v1, and better refused here than half-way through a funding proposal.
        // Funding resolves the selected account once and passes it here. Reading selection again
        // would let a concurrent account switch persist B as the owner while proposing the send
        // from A, after which reconciliation would search the wrong wallet.
        val account = sourceAccount ?: accountDataSource.getSelectedAccount()
        ensure(account !is KeystoneAccount, GiftCardCreationError.KEYSTONE_ACCOUNT_UNSUPPORTED)

        val synchronizer = synchronizerProvider.getSynchronizer()
        val networkName =
            GiftLinkCodec.networkName(synchronizer.network).orFail(GiftCardCreationError.UNSUPPORTED_NETWORK)

        // The chain tip, not the fully scanned height: this is where the recipient's scan begins,
        // and a birthday above the funding height would mean the note is never trial-decrypted.
        val tip = synchronizer.networkHeight.value.orFail(GiftCardCreationError.CHAIN_TIP_UNAVAILABLE)

        val keys = giftKeyProvider.mint(synchronizer.network)
        val now = Clock.System.now().toString()
        val card =
            StoredGiftCard(
                id = UUID.randomUUID().toString(),
                network = networkName,
                address = keys.address,
                mnemonic = keys.mnemonic,
                amountZatoshi = amount.value,
                birthdayHeight = tip.value,
                sourceAccountUuid = account.sdkAccount.accountUuid.toStorageKeyId(),
                createdAt = now,
                updatedAt = now,
                status = GiftCardStatus.DRAFT,
                expiresAt = expiresAt?.toString(),
                message = note,
            )

        giftCardStorageProvider.add(card)

        // Read back before returning. The caller's next step is to move money to an address only
        // this record can spend from, so "the write appeared to succeed" is not good enough.
        ensure(giftCardStorageProvider.get(card.id) == card, GiftCardCreationError.PERSIST_FAILED)

        return card
    }

    private fun ensure(condition: Boolean, error: GiftCardCreationError) {
        if (!condition) throw GiftCardCreationException(error)
    }

    private fun <T : Any> T?.orFail(error: GiftCardCreationError): T = this ?: throw GiftCardCreationException(error)
}
