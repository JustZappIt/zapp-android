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
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CheckGiftCardClaimedUseCase
import co.electriccoin.zcash.ui.common.usecase.ConfirmGiftCardFundingUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GiftCardCheckResult
import co.electriccoin.zcash.ui.common.usecase.ShareGiftLinkUseCase
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.common.wallet.toFiatString
import co.electriccoin.zcash.ui.common.wallet.zecFiatRate
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkCodec
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import co.electriccoin.zcash.ui.screen.gift.model.toLinkPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val checkGiftCardClaimed: CheckGiftCardClaimedUseCase,
    exchangeRateRepository: ExchangeRateRepository,
    swapRepository: SwapRepository,
    private val shareGiftLink: ShareGiftLinkUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val errorFlow = MutableStateFlow<GiftCardListError?>(null)
    private val noticeFlow = MutableStateFlow<GiftCardListNotice?>(null)
    private val isCorrupted = MutableStateFlow(false)
    private val checkingId = MutableStateFlow<String?>(null)
    private val checkProgress = MutableStateFlow<GiftCheckProgress?>(null)

    private var shareJob: Job? = null
    private var checkJob: Job? = null

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

    /**
     * Resolved the way the balance card resolves it — the opt-in rate first, then the swap
     * catalog's ZEC price — so a card never shows a figure the home screen disagrees with.
     * Null means show no fiat at all.
     */
    private val fiatRate =
        combine(exchangeRateRepository.state, swapRepository.assets) { rate, assets ->
            zecFiatRate(rate, assets.zecAsset?.usdPrice)
        }

    internal val state: StateFlow<GiftCardListState?> =
        combine(
            cards,
            fiatRate,
            combine(checkingId, checkProgress) { checking, progress -> checking to progress },
            // Paired only to stay inside combine's typed arity; they are unrelated slots.
            combine(errorFlow, noticeFlow) { err, notice -> err to notice },
            isCorrupted,
        ) { all, rate, (checking, progress), (err, notice), corrupted ->
            // A draft nothing was ever sent to is an artefact of minting before funding, not a card
            // the sender made. It cannot be handed out, checked, or recovered from — only clutter.
            val visible = all.filter { it.hasFundingAttempt }
            GiftCardListState(
                items = visible.sortedWith(DISPLAY_ORDER).map { toItem(it, rate, checking, progress) },
                isCorrupted = corrupted,
                error = err,
                notice = notice,
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

    private fun toItem(
        card: StoredGiftCard,
        rate: ZecFiatRate?,
        checkingId: String?,
        checkProgress: GiftCheckProgress?,
    ): GiftCardListItem {
        val status = card.listStatus()
        // An unfunded draft encodes into a link that looks real and pays nothing, and a collected
        // card's link is spent — both hand the recipient something worthless. Unresolved counts as
        // handable: the money may already have gone, and if it has, the link is the only route.
        val canHandOff = status != GiftCardListStatus.UNFUNDED && status != GiftCardListStatus.CLAIMED
        return GiftCardListItem(
            id = card.id,
            amount = stringRes(Zatoshi(card.amountZatoshi)),
            fiat = rate?.toFiatString(Zatoshi(card.amountZatoshi)),
            tier = giftCardTier(card.amountZatoshi, status == GiftCardListStatus.CLAIMED),
            createdAt = card.createdAt.toGiftDisplayDate(),
            message = card.message,
            status = status,
            expiry = card.expiresAt.toGiftExpiryDisplay(),
            lastCheckedAt = card.lastCheckedAt?.toGiftDisplayDate().takeIf { status != GiftCardListStatus.CLAIMED },
            check = card.checkControl(status, checkingId, checkProgress),
            handOff =
                GiftHandOff(
                    onShare = { picker -> onShare(card.id, picker) },
                    onCopy = { onCopy(card.id) },
                ).takeIf { canHandOff },
        )
    }

    /**
     * Shown for everything still in play, so rows do not silently differ; actionable only where
     * there is a funding to look for, and one card at a time because each check is a full scan.
     */
    private fun StoredGiftCard.checkControl(
        status: GiftCardListStatus,
        checkingId: String?,
        checkProgress: GiftCheckProgress?,
    ): GiftCheckControl =
        when {
            status == GiftCardListStatus.CLAIMED -> GiftCheckControl.Hidden
            id == checkingId -> GiftCheckControl.Running(checkProgress) { onCheck(id) }
            fundingTxid == null -> GiftCheckControl.Blocked(GiftCheckBlocked.NO_TRANSACTION)
            checkingId != null -> GiftCheckControl.Blocked(GiftCheckBlocked.ANOTHER_RUNNING)
            else -> GiftCheckControl.Ready { onCheck(id) }
        }

    private fun onShare(cardId: String, sharePickerText: String) {
        if (shareJob?.isActive == true) return
        shareJob =
            viewModelScope.launch {
                val link = linkFor(cardId) ?: return@launch
                clearMessages()
                // Only that the sheet went up. Whether the card counts as handed out is settled
                // later, by the chooser reporting the target the sender picked.
                if (!shareGiftLink(cardId = cardId, link = link, sharePickerText = sharePickerText)) {
                    errorFlow.value = GiftCardListError.SHARE_FAILED
                }
            }
    }

    /**
     * The hand-off that reports its own outcome.
     *
     * Sharing depends on the system telling us which target was picked, and a chooser that never
     * does leaves the card counted as unshared — still blocking the wallet reset. This is the route
     * the sender always has: the copy is an affirmative act, so the record follows it directly.
     */
    private fun onCopy(cardId: String) {
        if (shareJob?.isActive == true) return
        shareJob =
            viewModelScope.launch {
                val link = linkFor(cardId) ?: return@launch
                clearMessages()
                copyToClipboard(link, isSensitive = true)
                if (!shareGiftLink.markHandedOut(cardId)) {
                    errorFlow.value = GiftCardListError.HANDOFF_FAILED
                }
            }
    }

    /** Starts a check, or stops the one already running on this card. */
    private fun onCheck(cardId: String) {
        if (checkJob?.isActive == true) {
            // The scan can legitimately run for minutes (§11.1), so a stop is the only honest
            // control: there is no duration at which giving up is automatically right.
            checkJob?.cancel()
            return
        }
        checkJob =
            viewModelScope.launch {
                checkingId.value = cardId
                checkProgress.value = null
                clearMessages()
                try {
                    val card = runCatching { giftCardStorageProvider.get(cardId) }.getOrNull()
                    val result =
                        if (card == null) {
                            GiftCardCheckResult.UNKNOWN
                        } else {
                            checkGiftCardClaimed(card) { progress ->
                                checkProgress.value = GiftCheckProgress(progress.fraction.takeIf { it > 0f })
                            }
                        }
                    errorFlow.value =
                        when (result) {
                            GiftCardCheckResult.UNREACHABLE -> GiftCardListError.CHECK_UNREACHABLE
                            GiftCardCheckResult.UNKNOWN -> GiftCardListError.CHECK_FAILED
                            else -> null
                        }
                    // Not an error: the scan worked and the answer is "the money is not there yet".
                    noticeFlow.value =
                        GiftCardListNotice.CHECK_FUNDING_PENDING
                            .takeIf { result == GiftCardCheckResult.FUNDING_PENDING }
                } finally {
                    checkingId.value = null
                    checkProgress.value = null
                }
            }
    }

    private fun clearMessages() {
        errorFlow.value = null
        noticeFlow.value = null
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
        /** Cards that still need a hand-off first, then newest first within each group. */
        val DISPLAY_ORDER =
            compareByDescending<StoredGiftCard> { it.isUnsharedFunds }
                .thenByDescending { it.createdAt }
    }
}

private fun StoredGiftCard.listStatus(): GiftCardListStatus =
    when {
        status == GiftCardStatus.CLAIMED -> GiftCardListStatus.CLAIMED
        status == GiftCardStatus.SHARED -> GiftCardListStatus.SHARED
        status == GiftCardStatus.FUNDED -> GiftCardListStatus.FUNDED
        fundingTxid != null -> GiftCardListStatus.SUBMITTED
        fundingAttemptedAt != null -> GiftCardListStatus.UNRESOLVED
        else -> GiftCardListStatus.UNFUNDED
    }
