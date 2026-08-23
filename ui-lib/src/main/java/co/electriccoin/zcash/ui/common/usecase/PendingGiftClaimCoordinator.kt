// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.provider.ReceivedGiftStorageProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkIntake
import co.electriccoin.zcash.ui.screen.gift.model.PendingGiftLinkStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PendingGiftClaimCoordinator(
    private val receivedGiftStorageProvider: ReceivedGiftStorageProvider,
    private val confirmGiftClaim: ConfirmGiftClaimUseCase,
    private val pendingGiftLinks: PendingGiftLinkStore,
) {
    private val mutex = Mutex()

    suspend fun resumeNext(): String? =
        mutex.withLock {
            confirmGiftClaim.reconcile()
            receivedGiftStorageProvider
                .getAll()
                .firstOrNull { !it.isSettled && it.claimLink != null }
                ?.claimLink
                ?.let(GiftLinkCodec::encode)
                ?.let { pendingGiftLinks.put(it) as? GiftLinkIntake.Accepted }
                ?.token
        }
}
