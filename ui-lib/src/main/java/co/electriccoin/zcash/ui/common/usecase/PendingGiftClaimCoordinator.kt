// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkIntake
import co.electriccoin.zcash.ui.screen.gift.model.PendingGiftLinkStore
import co.electriccoin.zcash.ui.screen.gift.model.ReceivedGift
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PendingGiftClaimCoordinator(
    private val receivedGiftStorageProvider: ReceivedGiftStorageProvider,
    private val confirmGiftClaim: ConfirmGiftClaimUseCase,
    private val pendingGiftLinks: PendingGiftLinkStore,
) {
    private val mutex = Mutex()

    /**
     * The link of one claim that still has recovery work left, registered for a fresh claim screen.
     *
     * Scoped to receipts that actually started a claim ([ReceivedGift.isUnsettledClaim]). A receipt
     * is written before the scan, so an unscoped sweep also reopens this screen for every card the
     * wallet merely read — an unfunded one, one whose funding has not confirmed, one another holder
     * is mid-claim on. Nothing ever settles those, so the screen would come back on every single
     * foreground, for good, over a gift that was never taken.
     */
    suspend fun resumeNext(): String? =
        mutex.withLock {
            confirmGiftClaim.reconcile()
            receivedGiftStorageProvider
                .getAll()
                .firstOrNull { it.isUnsettledClaim && it.claimLink != null }
                ?.claimLink
                ?.let(GiftLinkCodec::encode)
                ?.let { pendingGiftLinks.put(it) as? GiftLinkIntake.Accepted }
                ?.token
        }
}
