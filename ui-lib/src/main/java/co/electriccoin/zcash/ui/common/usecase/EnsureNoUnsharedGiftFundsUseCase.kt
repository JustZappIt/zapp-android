// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import kotlinx.coroutines.CancellationException

/** Thrown before anything is deleted, so an unhandled one leaves the wallet intact. */
class UnsharedGiftFundsException : IllegalStateException("Unsettled gift custody would be destroyed")

/**
 * Refuses a destructive action while a gift card's link has never left the device.
 *
 * Every path that clears the encrypted preferences has to call this: `gift_cards_v1` lives there,
 * an unshared card's seed is random rather than derived from the wallet seed, and there is no
 * reclaim, so those preferences are the only copy. Shared rather than repeated, because a guard on
 * one destructive path and not another is the same bug with extra steps.
 */
class EnsureNoUnsharedGiftFundsUseCase(
    private val giftCardStorageProvider: GiftCardStorageProvider,
    private val receivedGiftStorageProvider: ReceivedGiftStorageProvider,
) {
    /** @throws UnsharedGiftFundsException before the caller has touched anything. */
    suspend operator fun invoke() {
        // An unreadable store blocks rather than passes: guessing "empty" wrong destroys money.
        val senderBlocked =
            runCatching { giftCardStorageProvider.hasUnsharedFunds() }
                .getOrElse { throwable ->
                    throwable.blockDestruction("Gift card store could not be read; refusing to destroy it")
                }
        val recipientBlocked =
            runCatching { receivedGiftStorageProvider.hasUnsettledClaims() }
                .getOrElse { throwable ->
                    throwable.blockDestruction("Received gift store could not be read; refusing to destroy it")
                }
        if (senderBlocked || recipientBlocked) throw UnsharedGiftFundsException()
    }
}

private fun Throwable.blockDestruction(message: String): Boolean {
    if (this is CancellationException) throw this
    Twig.error(this) { message }
    return true
}
