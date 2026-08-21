// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.GiftClaimOutcome
import co.electriccoin.zcash.ui.common.datasource.GiftClaimProgress
import co.electriccoin.zcash.ui.common.datasource.REQUIRED_CONFIRMATIONS
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.usecase.ClaimGiftCardUseCase
import co.electriccoin.zcash.ui.common.usecase.GiftClaimNotReadyException
import co.electriccoin.zcash.ui.screen.gift.model.GiftBirthdayVerdict
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkError
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkException
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkPayload
import co.electriccoin.zcash.ui.screen.gift.model.PendingGiftLinkStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Drives claiming a gift link.
 *
 * Two rules shape the whole class. The link is checked as far as possible *offline* before any
 * block is downloaded, so a tampered or wrong-network link never starts a scan. And the scan is
 * abandonable while the broadcast is not: leaving the app cancels a sync the recipient can simply
 * restart, but once a transaction is being submitted `GiftClaimDataSource` runs it to a verdict
 * regardless, because a card with no reclaim must never be left in "did that send?".
 */
class GiftClaimVM(
    args: GiftClaimArgs,
    pendingGiftLinks: PendingGiftLinkStore,
    private val claimGiftCard: ClaimGiftCardUseCase,
    private val applicationStateProvider: ApplicationStateProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    /** Taken once. Retries and confirmation re-checks re-read it from here, not from the store. */
    private val uri: String? = pendingGiftLinks.take(args.token)

    private val snapshot = MutableStateFlow(GiftClaimSnapshot())

    private var claimJob: Job? = null

    private var payload: GiftLinkPayload? = null

    internal val state: StateFlow<GiftClaimState> =
        snapshot
            .map { it.toState() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = GiftClaimSnapshot().toState(),
            )

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            applicationStateProvider.isInForeground.collect { isForeground ->
                // §3.5: a bearer seed must not keep scanning behind a lock screen. Backgrounding is
                // the signal — the lock overlay only appears on the way back in, and it sits above
                // the nav host, so a claim cannot be started from behind it either. Cancelling is
                // safe here precisely because the broadcast half is NonCancellable.
                if (!isForeground) stopClaim()
            }
        }
    }

    private suspend fun load() {
        // Only reachable when the process died with the claim on the back stack: the token survives
        // in saved instance state, the in-memory link does not.
        val link =
            uri ?: return snapshot.update {
                it.copy(stage = GiftClaimStage.PREVIEW, error = GiftClaimError.LINK_EXPIRED)
            }

        runCatching { claimGiftCard.preview(link) }
            .onSuccess { preview ->
                payload = preview.payload
                snapshot.update {
                    it.copy(
                        stage =
                            when (preview.verdict) {
                                GiftBirthdayVerdict.Proceed -> GiftClaimStage.PREVIEW
                                is GiftBirthdayVerdict.NeedsConsent -> GiftClaimStage.CONSENT
                            },
                        amount = Zatoshi(preview.payload.amountZatoshi.toLong()),
                        message = preview.payload.message,
                        expiry = preview.payload.expiresAt.toGiftExpiryDisplay(),
                        blocksToScan = (preview.verdict as? GiftBirthdayVerdict.NeedsConsent)?.blocksToScan,
                        error = null,
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                snapshot.update { it.copy(stage = GiftClaimStage.PREVIEW, error = throwable.toClaimError()) }
            }
    }

    private fun onClaim() {
        // isCompleted, not isActive: a cancelled claim stays incomplete while its NonCancellable
        // broadcast runs on, and a second claim started there would try to spend the same note.
        if (claimJob?.isCompleted == false) return
        val current = payload ?: return
        snapshot.update { it.copy(stage = GiftClaimStage.CLAIMING, progressFraction = null, error = null) }
        claimJob = viewModelScope.launch { claim(current) }
    }

    private suspend fun claim(payload: GiftLinkPayload) {
        runCatching { claimGiftCard(payload, ::onProgress) }
            .onSuccess { outcome ->
                snapshot.update { it.applying(outcome) }
                // Waiting on confirmations is a wait, not a failure. Re-checking on a timer is
                // what turns it into something the recipient can watch instead of something they
                // have to keep poking. The scan resumes against the retained database, so each
                // pass is cheap.
                if (outcome is GiftClaimOutcome.NotYetSpendable) scheduleConfirmationRecheck(payload)
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                Twig.error(throwable) { "Gift claim failed" }
                snapshot.update { it.copy(stage = GiftClaimStage.PREVIEW, error = GiftClaimError.FAILED) }
            }
    }

    private fun scheduleConfirmationRecheck(payload: GiftLinkPayload) {
        claimJob =
            viewModelScope.launch {
                delay(CONFIRMATION_RECHECK)
                if (snapshot.value.stage != GiftClaimStage.PENDING_CONFIRMATIONS) return@launch
                snapshot.update { it.copy(stage = GiftClaimStage.CLAIMING, progressFraction = null) }
                claim(payload)
            }
    }

    private fun onProgress(progress: GiftClaimProgress) = snapshot.update { it.applying(progress) }

    private fun stopClaim() {
        val job = claimJob ?: return
        if (job.isCompleted) return
        job.cancel()
        // The handle is kept rather than cleared. Cancelling only abandons the scan — a broadcast
        // already inside NonCancellable runs on, and onClaim() needs the handle to refuse a second
        // claim until it finishes.
        // Back to the preview so the recipient can restart deliberately. Nothing was lost: the
        // scan is resumable against the same per-card database on the next attempt.
        snapshot.update { it.copy(stage = GiftClaimStage.PREVIEW, progressFraction = null) }
    }

    private fun onConsent() {
        snapshot.update { it.copy(stage = GiftClaimStage.PREVIEW) }
    }

    private fun onRetry() {
        snapshot.update { it.copy(stage = GiftClaimStage.PREVIEW, error = null) }
        // Nothing ever loaded, so there is no card to retry claiming — re-read the link instead.
        // This is the path out of WALLET_NOT_READY once the synchronizer has connected.
        if (payload == null) {
            snapshot.update { it.copy(stage = GiftClaimStage.LOADING) }
            viewModelScope.launch { load() }
        }
    }

    private fun GiftClaimSnapshot.toState() =
        GiftClaimState(
            stage = stage,
            amount = amount,
            message = message,
            expiry = expiry,
            blocksToScan = blocksToScan,
            progressFraction = progressFraction,
            blocksRemaining = blocksRemaining,
            confirmations = confirmations,
            requiredConfirmations = requiredConfirmations,
            error = error,
            onClaim = ::onClaim,
            onConsent = ::onConsent,
            onRetry = ::onRetry,
            onBack = navigationRouter::back,
        )

    override fun onCleared() {
        stopClaim()
        super.onCleared()
    }

    private companion object {
        /** Roughly a testnet block, so the count moves visibly without hammering the server. */
        val CONFIRMATION_RECHECK = 45.seconds
    }
}

/**
 * Null until there is a real figure. The SDK reports 0f before it has measured anything, and a bar
 * pinned at 0% reads as broken where a sweep reads as working, so the switch happens on the first
 * non-zero value.
 */
private fun GiftClaimSnapshot.applying(progress: GiftClaimProgress) =
    copy(
        progressFraction = progress.fraction.takeIf { it > 0f },
        blocksRemaining =
            progress.tipHeight
                ?.let { tip -> progress.scannedHeight?.let { (tip - it).coerceAtLeast(0L) } },
    )

private data class GiftClaimSnapshot(
    val stage: GiftClaimStage = GiftClaimStage.LOADING,
    val amount: Zatoshi? = null,
    val message: String? = null,
    val expiry: GiftExpiryDisplay? = null,
    val blocksToScan: Long? = null,
    val progressFraction: Float? = null,
    val blocksRemaining: Long? = null,
    val confirmations: Int? = null,
    val requiredConfirmations: Int = REQUIRED_CONFIRMATIONS,
    val error: GiftClaimError? = null,
)

private fun GiftClaimSnapshot.applying(outcome: GiftClaimOutcome): GiftClaimSnapshot =
    when (outcome) {
        is GiftClaimOutcome.Claimed -> {
            copy(stage = GiftClaimStage.DONE, amount = outcome.amount, error = null)
        }

        is GiftClaimOutcome.NotYetSpendable -> {
            copy(
                stage = GiftClaimStage.PENDING_CONFIRMATIONS,
                confirmations = outcome.confirmations,
                requiredConfirmations = outcome.requiredConfirmations,
                error = null,
            )
        }

        GiftClaimOutcome.Empty -> {
            copy(stage = GiftClaimStage.EMPTY, error = null)
        }

        is GiftClaimOutcome.NotBroadcast -> {
            // The card is untouched and its database was retained, so this is a "try again",
            // never a "your gift is gone".
            copy(stage = GiftClaimStage.PREVIEW, error = GiftClaimError.NOT_BROADCAST)
        }
    }

private fun Throwable.toClaimError(): GiftClaimError {
    if (this is GiftClaimNotReadyException) return GiftClaimError.WALLET_NOT_READY
    return when ((this as? GiftLinkException)?.error) {
        GiftLinkError.NETWORK_MISMATCH -> GiftClaimError.WRONG_NETWORK
        GiftLinkError.ADDRESS_MISMATCH -> GiftClaimError.TAMPERED
        GiftLinkError.BIRTHDAY_ABOVE_TIP -> GiftClaimError.BIRTHDAY_ABOVE_TIP
        null -> GiftClaimError.FAILED
        else -> GiftClaimError.MALFORMED_LINK
    }
}
