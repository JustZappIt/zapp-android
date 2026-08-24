// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.GiftCardUnreachableException
import co.electriccoin.zcash.ui.common.datasource.GiftClaimOutcome
import co.electriccoin.zcash.ui.common.datasource.GiftClaimProgress
import co.electriccoin.zcash.ui.common.datasource.REQUIRED_CONFIRMATIONS
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.ProvingParamsProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.ClaimGiftCardUseCase
import co.electriccoin.zcash.ui.common.usecase.ConfirmGiftClaimUseCase
import co.electriccoin.zcash.ui.common.usecase.GiftClaimNotReadyException
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.common.wallet.toFiatString
import co.electriccoin.zcash.ui.common.wallet.zecFiatRate
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
import kotlinx.coroutines.flow.combine
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
@Suppress("TooManyFunctions")
class GiftClaimVM(
    args: GiftClaimArgs,
    private val pendingGiftLinks: PendingGiftLinkStore,
    private val claimGiftCard: ClaimGiftCardUseCase,
    private val confirmGiftClaim: ConfirmGiftClaimUseCase,
    private val applicationStateProvider: ApplicationStateProvider,
    private val provingParams: ProvingParamsProvider,
    exchangeRateRepository: ExchangeRateRepository,
    swapRepository: SwapRepository,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    /**
     * Taken once. Retries and confirmation re-checks re-read it from here, not from the store.
     *
     * Null covers both ways there is nothing to open: the store refused the link (no token), and
     * the process died with the claim on the back stack, where the token survives in saved instance
     * state and the in-memory link does not.
     */
    private val uri: String? = args.token?.let { pendingGiftLinks.take(it) }

    private val snapshot = MutableStateFlow(GiftClaimSnapshot())

    private var claimJob: Job? = null

    private var confirmationRetryJob: Job? = null

    /** Waits out finality for a claim already broadcast, then settles it. */
    private var claimConfirmJob: Job? = null

    /** Renders how far that wait has got. Separate because it never completes on its own. */
    private var claimConfirmProgressJob: Job? = null

    /** The claim this screen re-entered on, kept so a foreground can re-arm the wait above. */
    private var inFlightClaimTxids: List<String> = emptyList()

    /** What [claimConfirmJob] is currently waiting on, so a replacement claim can displace it. */
    private var awaitedClaimTxids: List<String> = emptyList()

    private var payload: GiftLinkPayload? = null

    /** Derived once in [load]; the link does not carry it. */
    private var cardAddress: String? = null

    /**
     * Whether a scan may start at all (§3.5).
     *
     * The lock overlay sits above the nav host, so a claim could not be tapped from behind it
     * anyway — but that is a fact about the view tree, and this rule is about a bearer seed
     * scanning behind a lock screen. Held as an invariant here so it survives a re-layered UI.
     * Starts false: the value arrives with the first collection below, and refusing a claim for the
     * moment before that is the safe direction to be wrong in.
     */
    @Volatile
    private var isForeground: Boolean = false

    internal val state: StateFlow<GiftClaimState> =
        combine(snapshot, exchangeRateRepository.state, swapRepository.assets) { snap, rate, assets ->
            snap.toState(zecFiatRate(rate, assets.zecAsset?.usdPrice))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = GiftClaimSnapshot().toState(rate = null),
        )

    init {
        viewModelScope.launch {
            load()
            // A recipient can arrive here on their first-ever launch, with the link tapped before
            // there is any wallet to claim into. Pick the card back up once onboarding has made
            // one, rather than leaving them on a screen whose offer has already been taken.
            if (snapshot.value.stage == GiftClaimStage.NEEDS_WALLET) {
                claimGiftCard.awaitWallet()
                snapshot.update { it.copy(stage = GiftClaimStage.LOADING) }
                load()
            }
        }
        viewModelScope.launch {
            applicationStateProvider.isInForeground.collect { foreground ->
                isForeground = foreground
                // §3.5: a bearer seed must not keep scanning behind a lock screen. Backgrounding is
                // the signal — the lock overlay only appears on the way back in. Cancelling is safe
                // here precisely because the broadcast half is NonCancellable. Starting is refused
                // separately, in onClaim.
                if (foreground) {
                    when (snapshot.value.stage) {
                        GiftClaimStage.PENDING_CONFIRMATIONS -> scheduleConfirmationRecheck(payload, cardAddress)
                        GiftClaimStage.CLAIM_CONFIRMING -> cardAddress?.let { awaitClaimFinality(it) }
                        else -> Unit
                    }
                } else {
                    stopClaim(forBackground = true)
                }
            }
        }
    }

    private suspend fun load() {
        val link =
            uri ?: return snapshot.update {
                it.copy(stage = GiftClaimStage.PREVIEW, error = GiftClaimError.LINK_UNAVAILABLE)
            }

        // Settle any receipt whose claim has since mined, so the lookup inside preview can tell a
        // finished claim from one still in flight. Nothing else on a recipient's device does this:
        // the in-flight confirm dies with this screen, and reconcile otherwise runs only on the
        // sender's card list — which a recipient never opens. Without it every receipt here stays
        // unsettled forever, holding a bearer secret it no longer needs.
        runCatching { confirmGiftClaim.reconcile() }

        runCatching { claimGiftCard.preview(link) }
            .onSuccess { preview ->
                payload = preview.payload
                cardAddress = preview.cardAddress
                // The card goes up now, on what the link alone says. Holding it back until the
                // wallet finds the chain is how a real gift spends its first half-minute looking
                // like a broken screen.
                snapshot.update {
                    it.copy(
                        stage = if (preview.hasWallet) GiftClaimStage.LOADING else GiftClaimStage.NEEDS_WALLET,
                        amount = Zatoshi(preview.payload.amountZatoshi.toLong()),
                        message = preview.payload.message,
                        expiry = preview.payload.expiresAt.toGiftExpiryDisplay(),
                        error = null,
                    )
                }
                if (preview.collected != null) {
                    // Already collected, on this wallet's own record. Nothing to scan for, nothing
                    // to spend, and no proving parameters needed to say so.
                    snapshot.update { it.applying(preview.collected) }
                } else if (preview.inFlightClaimTxids.isNotEmpty()) {
                    // This wallet already broadcast a claim for this card and it has not confirmed
                    // yet. The transaction is on this device's record, so re-running the claim
                    // could only resync the card's wallet to rediscover it — minutes of scanning
                    // for an answer already in hand, and a network error instead of it if the
                    // recipient happens to be offline. Show the wait, and finish it in the
                    // background. Proving parameters are irrelevant: nothing here builds a spend.
                    inFlightClaimTxids = preview.inFlightClaimTxids
                    snapshot.update { it.confirming() }
                    awaitClaimFinality(preview.cardAddress)
                } else {
                    // Start the 51MB proving-parameter download the moment a real card is on
                    // screen, rather than at the end of a claim that has already found the money.
                    // A recipient with no wallet spends the next minute in onboarding, which is
                    // the room this needs.
                    provingParams.prefetch()
                    if (preview.hasWallet) awaitVerdict(preview.payload)
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                snapshot.update {
                    it.copy(stage = GiftClaimStage.PREVIEW, canStopClaim = false, error = throwable.toClaimError())
                }
            }
    }

    /**
     * Resolves the scan cost, retrying for as long as the chain tip is unknown.
     *
     * A cold start has simply not found the chain yet, and the card on screen is already correct,
     * so this must not surface as a failure the recipient has to keep tapping at. Cancelled with
     * the scope the moment they leave.
     */
    private suspend fun awaitVerdict(payload: GiftLinkPayload) {
        while (true) {
            val attempt = runCatching { claimGiftCard.birthdayVerdict(payload) }
            val throwable = attempt.exceptionOrNull()
            if (throwable is CancellationException) throw throwable
            // An unknown chain tip is the only thing worth another pass. The card is already on
            // screen, so a cold start reads as a wait rather than as a verdict on the gift.
            if (throwable is GiftClaimNotReadyException) continue
            snapshot.update { it.applying(attempt) }
            return
        }
    }

    private fun onClaim() {
        // isCompleted, not isActive: a cancelled claim stays incomplete while its NonCancellable
        // broadcast runs on, and a second claim started there would try to spend the same note.
        // And §3.5 as a guard, rather than as a property of where the lock overlay happens to sit.
        if (claimJob?.isCompleted == false || !isForeground) return
        // Both are set together in load(), so one without the other is unreachable.
        val current = payload
        val address = cardAddress
        if (current == null || address == null) return
        snapshot.update {
            it.copy(
                stage = GiftClaimStage.CLAIMING,
                progressFraction = null,
                canStopClaim = true,
                error = null,
            )
        }
        claimJob = viewModelScope.launch { claim(current, address) }
    }

    private suspend fun claim(payload: GiftLinkPayload, address: String) {
        runCatching { claimGiftCard(payload, address) { progress -> snapshot.update { it.applying(progress) } } }
            .onSuccess { outcome ->
                snapshot.update { it.applying(outcome).keepingConfirming() }
                // Detached: the receipt keeps the link until this sees the claim on chain. Shares
                // the handle with the re-entry path so the two cannot both wait on one claim.
                if (outcome is GiftClaimOutcome.Claimed) {
                    inFlightClaimTxids = outcome.txIds
                    startClaimConfirmJob(address, outcome.txIds)
                }
                // Waiting on confirmations is a wait, not a failure. Re-checking on a timer is
                // what turns it into something the recipient can watch instead of something they
                // have to keep poking. The scan resumes against the retained database, so each
                // pass is cheap.
                if (outcome is GiftClaimOutcome.NotYetSpendable) scheduleConfirmationRecheck(payload, address)
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                Twig.error(throwable) { "Gift claim failed" }
                // Through the same mapper as the preview, so an unreachable server reads as one
                // here too rather than collapsing into a bare "something went wrong".
                snapshot.update {
                    it.copy(stage = GiftClaimStage.PREVIEW, error = throwable.toClaimError()).keepingConfirming()
                }
            }
    }

    /**
     * Holds [GiftClaimStage.CLAIM_CONFIRMING] against anything that would fall back to a preview.
     *
     * The re-check offered on that stage runs the ordinary claim path, and every way it can end
     * short — offline, an unclear broadcast, a stop — lands on [GiftClaimStage.PREVIEW], whose
     * action is "Claim". That is exactly the state the stage exists to prevent, two taps away from
     * it. The error still shows; only the framing is kept honest.
     */
    private fun GiftClaimSnapshot.keepingConfirming(): GiftClaimSnapshot =
        if (stage == GiftClaimStage.PREVIEW && inFlightClaimTxids.isNotEmpty()) {
            copy(stage = GiftClaimStage.CLAIM_CONFIRMING, progressFraction = null, canStopClaim = false)
        } else {
            this
        }

    /**
     * Arms the wait on a claim already broadcast: finality in one job, the confirmation count in
     * another.
     *
     * Two jobs because they end differently. The finality wait completes — that is the signal the
     * gift has landed — while the count is a flow that never does, so joining them would leave the
     * screen unable to tell the two apart.
     *
     * Neither touches the card's bearer seed until finality is reached, and both are cancelled on
     * background alongside every other network work this screen does (§3.5). Losing them costs
     * nothing: `ConfirmGiftClaimUseCase.reconcile` picks the receipt up on the next pass.
     */
    private fun awaitClaimFinality(address: String) {
        val txIds = inFlightClaimTxids
        // §3.5, and the same guard `onClaim` holds: the wait ends in `inspectFinalization`, which
        // opens the card's own wallet on its bearer seed. Refusing here is safe because the
        // foreground collector re-arms on the stage, so a load that resolves while backgrounded is
        // picked up on the way back in rather than lost.
        if (txIds.isEmpty() || !isForeground) return
        startClaimConfirmJob(address, txIds)
        if (claimConfirmProgressJob?.isActive == true) return
        claimConfirmProgressJob =
            viewModelScope.launch {
                runCatching {
                    confirmGiftClaim.observeClaimConfirmations(address).collect { confirmations ->
                        snapshot.update { snap ->
                            // Guarded: a re-check the recipient started can move the stage on while
                            // this is still collecting, and a count under a claiming bar is a lie.
                            if (snap.stage == GiftClaimStage.CLAIM_CONFIRMING) {
                                snap.copy(confirmations = confirmations)
                            } else {
                                snap
                            }
                        }
                    }
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    // The bar sweeps without a figure, which is what it does before the claim mines
                    // anyway. Nothing about the claim itself depends on this.
                    Twig.warn(throwable) { "Gift claim confirmations could not be counted" }
                }
            }
    }

    private fun startClaimConfirmJob(address: String, txIds: List<String>) {
        // Compared, not merely checked for liveness. A re-check that finds the first claim expired
        // submits a replacement, and a running wait on the dead transaction would otherwise keep
        // the new one from ever being watched — the screen would sit on a claim nothing settles.
        if (claimConfirmJob?.isActive == true && awaitedClaimTxids == txIds) return
        claimConfirmJob?.cancel()
        awaitedClaimTxids = txIds
        claimConfirmJob =
            viewModelScope.launch {
                runCatching { confirmGiftClaim(address, txIds) }
                    .onSuccess {
                        // Returning means finality was *observed* — the claim is on chain and the
                        // money is here. It does not prove the receipt settled: a residual top-up
                        // above the abandon threshold leaves it open, and then the card still has
                        // something on it and reopening this screen later is correct.
                        snapshot.update { snap ->
                            if (snap.stage == GiftClaimStage.CLAIM_CONFIRMING) {
                                snap.copy(stage = GiftClaimStage.DONE, confirmations = null, error = null)
                            } else {
                                snap
                            }
                        }
                    }.onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        // Not surfaced: the claim is broadcast and recorded either way, and
                        // reconcile retries this on the next foreground.
                        Twig.warn(throwable) { "Gift claim finality wait ended early" }
                    }
            }
    }

    private fun scheduleConfirmationRecheck(payload: GiftLinkPayload?, address: String?) {
        if (payload != null && address != null) {
            if (confirmationRetryJob?.isActive != true && isForeground) {
                confirmationRetryJob =
                    viewModelScope.launch {
                        delay(CONFIRMATION_RECHECK)
                        if (snapshot.value.stage != GiftClaimStage.PENDING_CONFIRMATIONS) return@launch
                        snapshot.update {
                            it.copy(stage = GiftClaimStage.CLAIMING, progressFraction = null, canStopClaim = true)
                        }
                        claimJob = viewModelScope.launch { claim(payload, address) }
                    }
            }
        }
    }

    private fun stopClaim(forBackground: Boolean = false) {
        if (forBackground) {
            confirmationRetryJob?.cancel()
            confirmationRetryJob = null
            // The finality wait ends in `inspectFinalization`, which opens the card's own wallet on
            // its bearer seed. §3.5 says that must not run behind a lock screen, and the stage is
            // retained so the foreground collector re-arms both.
            claimConfirmJob?.cancel()
            claimConfirmJob = null
            claimConfirmProgressJob?.cancel()
            claimConfirmProgressJob = null
        }

        val job = claimJob
        if (job != null && !job.isCompleted && snapshot.value.canStopClaim) {
            job.cancel()
            // The handle is kept rather than cleared. Cancelling only abandons a resumable scan,
            // and prevents a second attempt until cancellation has finished. PENDING_CONFIRMATIONS
            // and the local creation/submission phase both have canStopClaim=false, so backgrounding
            // retains the former for a foreground retry and lets the latter publish its outcome.
            snapshot.update { snap ->
                snap
                    .copy(stage = GiftClaimStage.PREVIEW, progressFraction = null, canStopClaim = false)
                    .keepingConfirming()
            }
        }
    }

    /**
     * Hands the link back to the store before leaving, because this screen's own token was spent
     * when it opened and onboarding is hosted by the root destination — the only way to reach it is
     * to pop this claim. `RootNavGraph` reopens the deferred link once the wallet lands.
     */
    private fun onCreateWallet() {
        uri?.let { pendingGiftLinks.defer(it) }
        navigationRouter.backToRoot()
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

    private fun GiftClaimSnapshot.toState(rate: ZecFiatRate?) =
        GiftClaimState(
            stage = stage,
            amount = amount,
            // Null wherever the wallet has no rate — opted out, or not loaded yet. Then no fiat.
            fiat = amount?.let { zec -> rate?.toFiatString(zec) },
            message = message,
            expiry = expiry,
            blocksToScan = blocksToScan,
            progressFraction = progressFraction,
            blocksRemaining = blocksRemaining,
            confirmations = confirmations,
            requiredConfirmations = requiredConfirmations,
            canStopClaim = canStopClaim,
            error = error,
            onClaim = ::onClaim,
            onConsent = { snapshot.update { snap -> snap.copy(stage = GiftClaimStage.PREVIEW) } },
            onRetry = ::onRetry,
            onCreateWallet = ::onCreateWallet,
            onStopClaim = { stopClaim() },
            onBack = navigationRouter::back,
        )

    override fun onCleared() {
        confirmationRetryJob?.cancel()
        claimConfirmJob?.cancel()
        claimConfirmProgressJob?.cancel()
        claimJob?.cancel()
        uri?.let { pendingGiftLinks.release(it) }
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
        canStopClaim = progress.status != Synchronizer.Status.SYNCED,
    )

/** Enters the wait on a claim already broadcast, clearing anything the previous stage was showing. */
private fun GiftClaimSnapshot.confirming() =
    copy(
        stage = GiftClaimStage.CLAIM_CONFIRMING,
        progressFraction = null,
        blocksRemaining = null,
        confirmations = null,
        canStopClaim = false,
        error = null,
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
    val canStopClaim: Boolean = false,
    val error: GiftClaimError? = null,
)

/** The scan cost once the chain tip is known, or the reason the link could not be judged. */
private fun GiftClaimSnapshot.applying(verdict: Result<GiftBirthdayVerdict>): GiftClaimSnapshot =
    verdict.fold(
        onSuccess = {
            copy(
                stage =
                    when (it) {
                        GiftBirthdayVerdict.Proceed -> GiftClaimStage.PREVIEW
                        is GiftBirthdayVerdict.NeedsConsent -> GiftClaimStage.CONSENT
                    },
                blocksToScan = (it as? GiftBirthdayVerdict.NeedsConsent)?.blocksToScan,
                error = null,
            )
        },
        onFailure = { copy(stage = GiftClaimStage.PREVIEW, error = it.toClaimError()) },
    )

private fun GiftClaimSnapshot.applying(outcome: GiftClaimOutcome): GiftClaimSnapshot =
    when (outcome) {
        is GiftClaimOutcome.Claimed -> {
            copy(stage = outcome.resultStage(), amount = outcome.amount, canStopClaim = false, error = null)
        }

        is GiftClaimOutcome.NotYetSpendable -> {
            copy(
                stage = outcome.resultStage(),
                confirmations = outcome.confirmations,
                requiredConfirmations = outcome.requiredConfirmations,
                canStopClaim = false,
                error = null,
            )
        }

        GiftClaimOutcome.AwaitingFunding -> {
            copy(stage = outcome.resultStage(), canStopClaim = false, error = null)
        }

        GiftClaimOutcome.AlreadyClaimed -> {
            copy(stage = outcome.resultStage(), canStopClaim = false, error = null)
        }

        is GiftClaimOutcome.NotBroadcast -> {
            // The card is untouched and its database was retained, so this is a "try again",
            // never a "your gift is gone".
            copy(stage = outcome.resultStage(), canStopClaim = false, error = GiftClaimError.NOT_BROADCAST)
        }

        // Also untouched and also retained, but not a "try again": nothing about the card changes
        // by waiting, so this must not schedule a re-check the way NotYetSpendable does.
        is GiftClaimOutcome.Underfunded -> {
            copy(stage = outcome.resultStage(), canStopClaim = false, error = GiftClaimError.UNDERFUNDED)
        }
    }

/** The screen each domain outcome owns; kept pure so every financially distinct result is testable. */
internal fun GiftClaimOutcome.resultStage(): GiftClaimStage =
    when (this) {
        is GiftClaimOutcome.Claimed -> GiftClaimStage.DONE
        is GiftClaimOutcome.NotYetSpendable -> GiftClaimStage.PENDING_CONFIRMATIONS
        GiftClaimOutcome.AwaitingFunding -> GiftClaimStage.AWAITING_FUNDING
        GiftClaimOutcome.AlreadyClaimed -> GiftClaimStage.ALREADY_CLAIMED
        is GiftClaimOutcome.NotBroadcast, is GiftClaimOutcome.Underfunded -> GiftClaimStage.PREVIEW
    }

private fun Throwable.toClaimError(): GiftClaimError =
    when {
        this is GiftClaimNotReadyException -> GiftClaimError.WALLET_NOT_READY

        // Separated from FAILED for the same reason the sender's check separates them: "you are
        // offline" and "something went wrong" need different copy, and neither says anything about
        // the card. Thrown out of the claim rather than the preview, which never touches the
        // network.
        this is GiftCardUnreachableException -> GiftClaimError.UNREACHABLE

        isMissingProvingParams() -> GiftClaimError.PARAMS_UNAVAILABLE

        else -> (this as? GiftLinkException)?.error.toClaimError()
    }

/**
 * Whether a claim died for want of the Sapling proving parameters.
 *
 * They are downloaded on first spend rather than shipped, and scanning never touches them, so this
 * lands at the last step of a claim that has already found the money.
 *
 * The typed exception only surfaces when the download itself throws; an absent or partial file
 * instead reaches Rust, which reports it as a string with no typed sub-code — the same limitation
 * `TransactionProgressVM.isAnchorError` works around. Replace the string check if the SDK ever
 * adds one.
 */
private fun Throwable.isMissingProvingParams(): Boolean {
    var throwable: Throwable? = this
    while (throwable != null) {
        val fetchFailed = throwable is TransactionEncoderException.FetchParamsException
        val rustRejectedTheFile =
            (throwable as? TransactionEncoderException.TransactionNotCreatedException)
                ?.rootCause
                ?.message
                ?.contains("parameter file", ignoreCase = true) == true
        if (fetchFailed || rustRejectedTheFile) return true
        throwable = throwable.cause
    }
    return false
}

/** Null is anything that was not a link rejection at all. */
private fun GiftLinkError?.toClaimError(): GiftClaimError =
    when (this) {
        GiftLinkError.NETWORK_MISMATCH -> GiftClaimError.WRONG_NETWORK

        GiftLinkError.BIRTHDAY_ABOVE_TIP -> GiftClaimError.BIRTHDAY_ABOVE_TIP

        // Both mean the link came from something this build does not understand, and both leave a
        // funded card the sender cannot be asked to re-mint — there is no reclaim.
        GiftLinkError.NEWER_FORMAT, GiftLinkError.UNSUPPORTED_VERSION -> GiftClaimError.NEWER_FORMAT

        null -> GiftClaimError.FAILED

        else -> GiftClaimError.MALFORMED_LINK
    }
