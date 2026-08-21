// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import android.content.Intent
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Hands a gift link out and records that it left the device.
 *
 * "Handed out" is what the shared status means, and it is deliberately generous: the clipboard is
 * as much a hand-off as the chooser is, so [markHandedOut] covers both. Treating a copied link as
 * still private would block deleting the source account forever over a card that has in fact
 * already been given away.
 *
 * The link is the money, so it never reaches a log, a notification or a crash report. Only the card
 * id is loggable.
 */
class ShareGiftLinkUseCase(
    private val context: Context,
    private val giftCardStorageProvider: GiftCardStorageProvider,
) {
    /** Opens the system chooser with [link]. Returns false if no chooser could be shown. */
    suspend operator fun invoke(cardId: String, link: String, sharePickerText: String): Boolean {
        val shared =
            runCatching {
                val shareIntent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = PLAIN_TEXT_MIME_TYPE
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                context.startActivity(
                    Intent.createChooser(shareIntent, sharePickerText).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }.isSuccess

        if (shared) markHandedOut(cardId)
        return shared
    }

    /**
     * Records that the link has left the device by some route other than the chooser — today, the
     * clipboard.
     */
    suspend fun markHandedOut(cardId: String) {
        runCatching {
            giftCardStorageProvider.markShared(id = cardId, at = Clock.System.now().toString())
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            // The link is already out; failing to record that must not look like a failed share.
            Twig.warn { "Gift card $cardId could not be marked shared" }
        }
    }

    private companion object {
        const val PLAIN_TEXT_MIME_TYPE = "text/plain"
    }
}
