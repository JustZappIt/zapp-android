// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.GiftCardStorageProvider
import co.electriccoin.zcash.ui.common.usecase.ConfirmGiftCardFundingUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareGiftLinkUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import co.electriccoin.zcash.ui.screen.gift.model.toLinkPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlin.time.Clock

/**
 * The recovery path for cards the sender never finished handing out.
 *
 * A card's ephemeral seed is random rather than derived from the wallet seed and there is no
 * reclaim, so leaving the create flow — by pressing Done, or by the process dying on the ready
 * screen — must not be the end of the story. Every stored card is re-shareable from here, because
 * [StoredGiftCard] keeps everything the link needs.
 */
class GiftCardListVM(
    private val giftCardStorageProvider: GiftCardStorageProvider,
    private val confirmGiftCardFunding: ConfirmGiftCardFundingUseCase,
    private val shareGiftLink: ShareGiftLinkUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val isShowingArchived = MutableStateFlow(false)
    private val copiedId = MutableStateFlow<String?>(null)
    private val errorFlow = MutableStateFlow<GiftCardListError?>(null)
    private val isCorrupted = MutableStateFlow(false)

    private var copyFeedbackJob: Job? = null
    private var shareJob: Job? = null

    private val cards =
        giftCardStorageProvider
            .observe()
            .catch { throwable ->
                // Never rethrow: a store that will not decode still has to render, or the sender
                // loses the one screen that could tell them something is wrong with it.
                Twig.error(throwable) { "Gift card list could not be read" }
                isCorrupted.value = true
                emit(emptyList())
            }

    internal val state: StateFlow<GiftCardListState?> =
        combine(
            cards,
            isShowingArchived,
            copiedId,
            errorFlow,
            isCorrupted,
        ) { all, showArchived, copied, err, corrupted ->
            val visible = all.filter { showArchived || it.archivedAt == null }
            GiftCardListState(
                items = visible.sortedWith(DISPLAY_ORDER).map { toItem(it, copied) },
                isCorrupted = corrupted,
                hasArchived = all.any { it.archivedAt != null },
                isShowingArchived = showArchived,
                error = err,
                onToggleArchived = { isShowingArchived.value = !isShowingArchived.value },
                onBack = navigationRouter::back,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    init {
        // Anything whose funding mined while nothing was watching still reads as a draft on disk.
        viewModelScope.launch { runCatching { confirmGiftCardFunding.reconcile() } }
    }

    private fun toItem(card: StoredGiftCard, copiedId: String?): GiftCardListItem {
        val status = card.listStatus()
        // An unfunded draft encodes into a link that looks real and pays nothing, and the recipient
        // has no way to tell. Unresolved counts as handable: the money may already have gone, and
        // if it has, the link is the only route to it.
        val canHandOff = status != GiftCardListStatus.UNFUNDED
        return GiftCardListItem(
            id = card.id,
            amount = stringRes(Zatoshi(card.amountZatoshi)),
            createdAt = card.createdAt.toDisplayDate(),
            message = card.message,
            status = status,
            isArchived = card.archivedAt != null,
            isCopied = card.id == copiedId,
            onCopy = { onCopy(card.id) }.takeIf { canHandOff },
            onShare = { picker: String -> onShare(card.id, picker) }.takeIf { canHandOff },
            // Archiving a card that still counts as unshared funds would hide the very record that
            // blocks the wallet wipe, so that card stays on the list until its link is handed out.
            onArchive = { onArchive(card.id) }.takeIf { card.archivedAt == null && !card.isUnsharedFunds },
        )
    }

    private fun onCopy(cardId: String) {
        viewModelScope.launch {
            val link = linkFor(cardId) ?: return@launch
            copyToClipboard(link, isSensitive = true)
            shareGiftLink.markHandedOut(cardId)
            errorFlow.value = null
            copiedId.value = cardId
            copyFeedbackJob?.cancel()
            copyFeedbackJob =
                viewModelScope.launch {
                    delay(COPY_FEEDBACK_DURATION_MS)
                    copiedId.value = null
                }
        }
    }

    private fun onShare(cardId: String, sharePickerText: String) {
        if (shareJob?.isActive == true) return
        shareJob =
            viewModelScope.launch {
                val link = linkFor(cardId) ?: return@launch
                errorFlow.value = null
                if (!shareGiftLink(cardId = cardId, link = link, sharePickerText = sharePickerText)) {
                    errorFlow.value = GiftCardListError.SHARE_FAILED
                }
            }
    }

    private fun onArchive(cardId: String) {
        viewModelScope.launch {
            runCatching { giftCardStorageProvider.archive(id = cardId, at = Clock.System.now().toString()) }
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Twig.warn { "Gift card $cardId could not be archived" }
                }
        }
    }

    /**
     * Rebuilds the link from the persisted record. Reads the card back rather than closing over it,
     * so a hand-off always encodes what is on disk now.
     */
    private suspend fun linkFor(cardId: String): String? =
        runCatching {
            val card = checkNotNull(giftCardStorageProvider.get(cardId)) { "No gift card $cardId" }
            GiftLinkCodec.encode(card.toLinkPayload())
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            // Log the throwable, never a message that interpolates it: a codec failure embeds the
            // payload it choked on, which is the mnemonic.
            Twig.error(throwable) { "Gift card $cardId link could not be rebuilt" }
            errorFlow.value = GiftCardListError.LINK_FAILED
            null
        }

    private companion object {
        const val COPY_FEEDBACK_DURATION_MS = 2_000L

        /** Cards that still need a hand-off first, then newest first within each group. */
        val DISPLAY_ORDER =
            compareByDescending<StoredGiftCard> { it.isUnsharedFunds }
                .thenByDescending { it.createdAt }
    }
}

private fun StoredGiftCard.listStatus(): GiftCardListStatus =
    when {
        status == GiftCardStatus.SHARED -> GiftCardListStatus.SHARED
        status == GiftCardStatus.FUNDED -> GiftCardListStatus.FUNDED
        fundingTxid != null -> GiftCardListStatus.SUBMITTED
        fundingAttemptedAt != null -> GiftCardListStatus.UNRESOLVED
        else -> GiftCardListStatus.UNFUNDED
    }

// A record written by a build that stamped something else must not take the screen down with it.
private fun String.toDisplayDate() =
    try {
        stringResByDateTime(ZonedDateTime.parse(this), useFullFormat = true)
    } catch (_: DateTimeParseException) {
        null
    }
