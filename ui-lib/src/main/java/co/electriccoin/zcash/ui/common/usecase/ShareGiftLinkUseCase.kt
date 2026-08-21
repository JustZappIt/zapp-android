// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import co.electriccoin.zcash.spackle.AndroidApiVersion
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock

/**
 * Hands a gift link out and records that it left the device.
 *
 * Opening the chooser is not the hand-off, and treating it as one is what this is built around.
 * The record of a hand-off is what releases the reset guard, and the reset wipes `gift_cards_v1` —
 * the only copy of an unshared card's ephemeral seed, which is random rather than derived from the
 * wallet seed. A sender who opened the sheet and changed their mind would otherwise have a card
 * marked as given away, holding real money, one wallet reset from gone.
 *
 * So the chooser reports back instead: [Intent.createChooser] takes an [IntentSender] the system
 * fires with the target the user actually picked, and only that fires [GiftShareCompletionReceiver].
 * Cancelling the sheet sends nothing and leaves the card protected. The receiver is a manifest
 * component rather than a registered callback because the sheet outlives the screen that opened it
 * — and, on a cold chooser, sometimes the process.
 *
 * The residual gap is honest and small: picking a target is not proof of sending, so a sender who
 * chooses a chat and then abandons it still marks the card handed off. That is the same trade the
 * clipboard makes in [markHandedOut], and it is the trade worth making — the alternative is a guard
 * nothing can clear, which blocks resetting the wallet forever.
 *
 * The link is the money, so it never reaches a log, a notification or a crash report. Only the card
 * id is loggable.
 */
class ShareGiftLinkUseCase(
    private val context: Context,
    private val giftCardStorageProvider: GiftCardStorageProvider,
) {
    /**
     * Opens the system chooser with [link]. Returns false if no chooser could be shown.
     *
     * A true here means the sheet went up, nothing more. The card is marked handed off later, if
     * and when the user picks a target.
     */
    operator fun invoke(cardId: String, link: String, sharePickerText: String): Boolean =
        runCatching {
            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = PLAIN_TEXT_MIME_TYPE
                    putExtra(Intent.EXTRA_TEXT, link)
                }
            context.startActivity(
                Intent
                    .createChooser(shareIntent, sharePickerText, chosenTargetCallback(cardId))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }.isSuccess

    /**
     * Records that the link has left the device by some route other than the chooser — today, the
     * clipboard, which is an affirmative act by the sender and reports no outcome of its own.
     *
     * Returns whether the record was updated. Callers that can still tell the sender something
     * should: the link is already out either way, but a card that is not marked handed off keeps
     * blocking the wallet reset, and silently doing that is how [ShareGiftLinkUseCase]'s guard turns
     * into a wallet nobody can delete.
     */
    suspend fun markHandedOut(cardId: String): Boolean =
        runCatching {
            giftCardStorageProvider.markShared(id = cardId, at = Clock.System.now().toString())
        }.fold(
            onSuccess = { true },
            onFailure = { throwable ->
                if (throwable is CancellationException) throw throwable
                // The link is already out; failing to record that must not look like a failed share.
                Twig.error(throwable) { "Gift card $cardId could not be marked shared" }
                false
            }
        )

    /**
     * The sender the chooser fires once a target is picked.
     *
     * Keyed on the card so two sheets cannot overwrite each other's callback, and mutable on API 31+
     * because the system fills [Intent.EXTRA_CHOSEN_COMPONENT] into it — an immutable one would
     * arrive, but as a promise the platform is not allowed to complete.
     */
    private fun chosenTargetCallback(cardId: String): IntentSender {
        val callback =
            Intent(context, GiftShareCompletionReceiver::class.java)
                .putExtra(GiftShareCompletionReceiver.EXTRA_CARD_ID, cardId)
        val flags =
            if (AndroidApiVersion.isAtLeastS) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        return PendingIntent
            .getBroadcast(context, cardId.hashCode(), callback, flags)
            .intentSender
    }

    private companion object {
        const val PLAIN_TEXT_MIME_TYPE = "text/plain"
    }
}

/**
 * Marks a card handed off once the chooser reports which target the sender picked.
 *
 * Manifest-declared and not exported: the chooser fires this through a [PendingIntent] this app
 * owns, so it needs to survive the screen — and the process — that opened the sheet. A dynamically
 * registered receiver would die with either, and dying means a card that was given away stays
 * counted as unshared funds forever.
 */
class GiftShareCompletionReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val giftCardStorageProvider: GiftCardStorageProvider by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val cardId = intent.getStringExtra(EXTRA_CARD_ID) ?: return
        // The write is suspending and onReceive is not, so the broadcast is held open across it.
        // Nothing else in the app is guaranteed to be alive to do this instead.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    giftCardStorageProvider.markShared(id = cardId, at = Clock.System.now().toString())
                }.onFailure { throwable ->
                    Twig.error(throwable) { "Gift card $cardId could not be marked shared" }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CARD_ID = "co.electriccoin.zcash.ui.GIFT_CARD_ID"
    }
}
