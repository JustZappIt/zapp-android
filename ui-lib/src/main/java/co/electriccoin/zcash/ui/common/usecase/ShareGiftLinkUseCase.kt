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
import co.electriccoin.zcash.ui.common.bestEffort
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
 * Opening the chooser is not the hand-off, and the whole design turns on that. The record of a
 * hand-off releases the reset guard, and the reset wipes `gift_cards_v1` — the only copy of an
 * unshared card's seed. A sender who opened the sheet and changed their mind would otherwise be
 * left with a card marked as given away, holding real money, one wallet reset from gone.
 *
 * So the chooser reports back: [Intent.createChooser] takes an [IntentSender] the system fires with
 * the target actually picked, and only that fires [GiftShareCompletionReceiver]. Cancelling the
 * sheet sends nothing and leaves the card protected.
 *
 * The residual gap is small and deliberate: picking a target is not proof of sending. The same
 * trade [markHandedOut] makes, and worth making — the alternative is a guard nothing can clear.
 *
 * The link is the money, so it never reaches a log, a notification or a crash report.
 */
class ShareGiftLinkUseCase(
    private val context: Context,
    private val giftCardStorageProvider: GiftCardStorageProvider,
) {
    /**
     * Opens the system chooser with [link]. True means the sheet went up, nothing more — the card is
     * marked handed off later, if and when the user picks a target.
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
     * Records that the link left by some route other than the chooser — today the clipboard, which
     * is an affirmative act reporting no outcome of its own.
     *
     * Callers that can tell the sender the record failed should: the link is out either way, but an
     * unmarked card keeps blocking the wallet reset, and doing that silently is how the guard turns
     * into a wallet nobody can delete.
     */
    suspend fun markHandedOut(cardId: String): Boolean =
        // The link is already out; failing to record that must not look like a failed share.
        bestEffort("Gift card $cardId could not be marked shared") {
            giftCardStorageProvider.markShared(id = cardId, at = Clock.System.now().toString())
        }

    /**
     * Keyed on the card so two sheets cannot overwrite each other's callback, and mutable on API 31+
     * because the system fills [Intent.EXTRA_CHOSEN_COMPONENT] into it — an immutable one arrives,
     * but as a promise the platform is not allowed to complete.
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
 * Manifest-declared and not exported, so it survives the screen — and, on a cold chooser, the
 * process — that opened the sheet. A dynamically registered receiver dies with either, and dying
 * means a card that was given away stays counted as unshared funds forever.
 */
class GiftShareCompletionReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val giftCardStorageProvider: GiftCardStorageProvider by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val cardId = intent.getStringExtra(EXTRA_CARD_ID) ?: return
        // The write suspends and onReceive does not, so the broadcast is held open across it.
        // Nothing else in the app is guaranteed to be alive to do this instead.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                bestEffort("Gift card $cardId could not be marked shared") {
                    giftCardStorageProvider.markShared(id = cardId, at = Clock.System.now().toString())
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
