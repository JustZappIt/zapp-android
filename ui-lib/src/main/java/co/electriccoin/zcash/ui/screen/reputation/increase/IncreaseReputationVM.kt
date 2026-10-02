// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.identity.IdentityFailure
import xyz.justzappit.offramp.identity.IdentityReturn
import xyz.justzappit.offramp.identity.IdentityReturnSignal
import xyz.justzappit.offramp.identity.IdentityStatus
import xyz.justzappit.offramp.identity.IdentityVerificationDriver
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reclaim.ReclaimFailure
import xyz.justzappit.offramp.reclaim.ReclaimLaunchSignal
import xyz.justzappit.offramp.reclaim.ReclaimStatus
import xyz.justzappit.offramp.reclaim.ReclaimVerificationDriver
import xyz.justzappit.offramp.reputation.IdentityCheck
import xyz.justzappit.offramp.reputation.ReputationReader
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.SocialPlatform
import java.security.SecureRandom

/**
 * The verification list, and the run one row starts.
 *
 * Everything the list shows is read on chain: which accounts are already verified, and what each
 * one is worth. The §3.1 table is today's configuration, not a constant, and a wrong number here
 * is a promise about money.
 */
@Suppress("TooManyFunctions")
internal class IncreaseReputationVM(
    args: IncreaseReputationArgs,
    private val navigationRouter: NavigationRouter,
    private val accountProvider: SmartOfframpAccountProvider,
    private val reputationReader: ReputationReader,
    private val verificationDriver: ReclaimVerificationDriver,
    private val identityDriver: IdentityVerificationDriver,
    private val identityReturns: IdentityReturnInbox,
) : ViewModel() {
    private val currency = args.currency
    private val resumeSession =
        args.reclaimSessionId?.let { sessionId ->
            SocialPlatform.entries
                .firstOrNull { it.name == args.reclaimPlatform }
                ?.let { platform -> platform to sessionId }
        }
    private var summary: ReputationSummary? = null
    private var loadJob: Job? = null
    private var runJob: Job? = null
    private var launchSignal: ReclaimLaunchSignal? = null
    private var ready: ReclaimStatus.Ready? = null
    private var lastActiveStage = VerificationStage.READY

    private var identityCheck: IdentityCheck? = null
    private var returnSignal: IdentityReturnSignal? = null
    private var returnGraceJob: Job? = null
    private var widgetUrl: String? = null
    private var widgetOpened = false

    private val mutableState =
        MutableStateFlow(
            IncreaseReputationState(
                isLoading = true,
                platforms = emptyList(),
                identityChecks = emptyList(),
                run = null,
                error = null,
                primaryAction = null,
                secondaryAction = null,
                onBack = ::onBack,
                onRetryLoad = ::load,
            ),
        )
    val state: StateFlow<IncreaseReputationState> = mutableState

    init {
        load()
        resumeSession?.let { (platform, sessionId) -> resumeRun(platform, sessionId) }
        // The widget's redirect lands here whether or not a run is waiting: a live run takes it
        // through its signal, a cold-started screen resumes from it.
        identityReturns.returns
            .filterNotNull()
            .onEach { onIdentityReturn() }
            .launchIn(viewModelScope)
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        mutableState.update { it.copy(isLoading = true, error = null) }
        loadJob =
            viewModelScope.launch {
                try {
                    val read = reputationReader.read(accountProvider.resolve().address, currency)
                    summary = read
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            platforms = rows(read),
                            identityChecks = identityRows(read),
                        )
                    }
                    if (runJob?.isActive != true) {
                        identityDriver.recoverableCheck(currency)?.let(::startIdentityRun)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    // Broad on purpose: any read failure means the same thing to the user, and the
                    // reason belongs in the log rather than on screen.
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Twig.warn(e) { "Could not read verification state" }
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            error = stringRes(R.string.reputation_unreadable_body),
                            primaryAction =
                                ButtonState(
                                    text = stringRes(R.string.reputation_retry),
                                    onClick = ::load,
                                ),
                        )
                    }
                }
            }
    }

    private fun rows(read: ReputationSummary): List<VerifiableRow> =
        SocialPlatform.entries
            .filter { it != SocialPlatform.Binance || !isBinanceHidden() }
            .map { platform ->
                val isVerified = platform in read.verified
                VerifiableRow(
                    name = stringRes(platform.onChainName),
                    reward = reward(isVerified, read.award(platform).toString()),
                    limitGain = read.limitGainFor(platform)?.let(::limitGain),
                    subtitle =
                        if (platform.requiresMatureAccount && !isVerified) {
                            stringRes(R.string.increase_reputation_age_requirement)
                        } else {
                            null
                        },
                    isVerified = isVerified,
                    // Verified rows stay listed and inert: hiding one reads as a bug, and the user
                    // has no other place that says the account is already spent.
                    onClick = { if (!isVerified) startRun(platform) },
                )
            }

    private fun identityRows(read: ReputationSummary): List<VerifiableRow> =
        IdentityCheck.entries
            .filter { identityDriver.isOffered(it, currency) }
            .map { check ->
                val isVerified = check in read.identityVerified
                VerifiableRow(
                    name = identityName(check),
                    reward = reward(isVerified, read.award(check).toString()),
                    limitGain = read.limitGainFor(check)?.let(::limitGain),
                    subtitle =
                        stringRes(
                            when (check) {
                                IdentityCheck.Liveness -> R.string.increase_reputation_identity_liveness_subtitle
                                IdentityCheck.Passport -> R.string.increase_reputation_identity_passport_subtitle
                            },
                        ),
                    isVerified = isVerified,
                    onClick = { if (!isVerified) startIdentityRun(check) },
                )
            }

    private fun reward(isVerified: Boolean, points: String): StringResource =
        if (isVerified) {
            stringRes(R.string.increase_reputation_verified)
        } else {
            stringRes(R.string.increase_reputation_reward, points)
        }

    private fun limitGain(gain: Usdc6): StringResource =
        stringRes(R.string.increase_reputation_limit_gain, gain.toDisplayString(stripTrailingZeros = true))

    /**
     * p2p.me's own client hides Binance in India, so an INR user who tried it would meet a failure
     * we could have predicted. The corridor is the country signal Zapp actually has — the user is
     * buying with rupees — and it beats a device locale, which says where the phone was set up.
     */
    private fun isBinanceHidden(): Boolean = currency == CurrencyCode.Inr

    private fun startRun(platform: SocialPlatform) {
        if (runJob?.isActive == true) return
        val signal = ReclaimLaunchSignal()
        launchSignal = signal
        ready = null
        collectRun(platform, verificationDriver.verify(platform, currency, signal))
    }

    private fun resumeRun(platform: SocialPlatform, sessionId: String) {
        if (runJob?.isActive == true) return
        launchSignal = null
        ready = null
        collectRun(platform, verificationDriver.resume(platform, currency, sessionId))
    }

    private fun collectRun(platform: SocialPlatform, statuses: Flow<ReclaimStatus>) {
        identityCheck = null
        runJob =
            statuses
                .onEach { status -> onStatus(platform, status) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    Twig.warn(e) { "Verification of ${platform.onChainName} failed" }
                    onStatus(platform, ReclaimStatus.Failed(ReclaimFailure.Network))
                }.launchIn(viewModelScope)
    }

    private fun onStatus(platform: SocialPlatform, status: ReclaimStatus) {
        when (status) {
            ReclaimStatus.Preparing -> {
                emitRun(platform, VerificationStage.PREPARING)
            }

            is ReclaimStatus.Ready -> {
                ready = status
                emitRun(platform, VerificationStage.READY)
            }

            ReclaimStatus.Verifying -> {
                emitRun(platform, VerificationStage.VERIFYING)
            }

            ReclaimStatus.Submitting -> {
                emitRun(platform, VerificationStage.SUBMITTING)
            }

            is ReclaimStatus.Done -> {
                onVerified(status.summary)
                emitRun(platform, VerificationStage.DONE, summary = status.summary)
            }

            is ReclaimStatus.Failed -> {
                emitRun(platform, VerificationStage.FAILED, failure = status.reason)
            }
        }
    }

    private fun emitRun(
        platform: SocialPlatform,
        stage: VerificationStage,
        summary: ReputationSummary? = null,
        failure: ReclaimFailure? = null,
    ) {
        if (stage in ACTIVE_STAGES) lastActiveStage = stage
        publish(
            VerificationRun(
                isIdentityCheck = false,
                stage = stage,
                steps = verificationSteps(stage, lastActiveStage),
                message = message(platform, stage),
                error = failure?.let(::failureMessage),
                launchUrl = ready?.requestUrl,
                installIntentUrl = ready?.installIntentUrl,
                storeUrl = ready?.storeUrl,
                newPoints = summary?.points?.toString(),
                newBuyLimit = summary?.let(::newBuyLimit),
            ),
        )
    }

    private fun message(platform: SocialPlatform, stage: VerificationStage): StringResource =
        when (stage) {
            VerificationStage.PREPARING -> stringRes(R.string.increase_reputation_preparing)
            VerificationStage.READY -> stringRes(R.string.increase_reputation_ready, platform.onChainName)
            VerificationStage.VERIFYING -> stringRes(R.string.increase_reputation_waiting)
            VerificationStage.SUBMITTING -> stringRes(R.string.increase_reputation_saving)
            VerificationStage.DONE -> stringRes(R.string.increase_reputation_done, platform.onChainName)
            VerificationStage.FAILED -> stringRes(R.string.increase_reputation_failed)
        }

    private fun failureMessage(failure: ReclaimFailure): StringResource =
        when (failure) {
            ReclaimFailure.NotConfigured -> stringRes(R.string.increase_reputation_error_unavailable)
            ReclaimFailure.CriteriaNotMet -> stringRes(R.string.increase_reputation_error_criteria)
            ReclaimFailure.ProofGenerationFailed -> stringRes(R.string.increase_reputation_error_proof)
            ReclaimFailure.SessionExpired -> stringRes(R.string.increase_reputation_error_expired)
            ReclaimFailure.AlreadyVerifiedElsewhere -> stringRes(R.string.increase_reputation_error_already_used)
            ReclaimFailure.AddressMismatch -> stringRes(R.string.increase_reputation_error_mismatch)
            ReclaimFailure.VerificationRejected -> stringRes(R.string.increase_reputation_error_rejected)
            ReclaimFailure.SponsorshipUnavailable -> stringRes(R.string.increase_reputation_error_gas)
            ReclaimFailure.Network -> stringRes(R.string.increase_reputation_error_network)
        }

    private fun startIdentityRun(check: IdentityCheck) {
        if (runJob?.isActive == true) return
        val signal = IdentityReturnSignal()
        returnSignal = signal
        widgetOpened = false
        collectIdentityRun(check, identityDriver.verify(check, currency, nonce(), signal))
    }

    /** The driver validates persisted authorization before resuming a redirect without a live run. */
    private fun resumeIdentityRun(ret: IdentityReturn) {
        if (runJob?.isActive == true) return
        returnSignal = null
        widgetOpened = true
        collectIdentityRun(ret.check, identityDriver.resume(ret, currency))
    }

    private fun onIdentityReturn() {
        val ret = identityReturns.take() ?: return
        val signal = returnSignal
        when {
            signal != null && runJob?.isActive == true -> signal.deliver(ret)

            runJob?.isActive != true -> resumeIdentityRun(ret)

            // A Reclaim run owns the screen. The check is not lost: the user can take it again.
            else -> Unit
        }
    }

    /**
     * The widget hands its result back only by redirecting the browser to Zapp, and only while the
     * browser is in front: a user who switches back by hand leaves the redirect behind them, and
     * the one-time code it carried cannot be replayed. A redirect on its way lands before this
     * screen resumes, so a run still waiting a moment later has nothing coming. Say so, instead of
     * a step that spins for good; a late redirect still finishes the run it finds.
     */
    fun onScreenVisible() {
        if (!isAwaitingWidgetReturn()) return
        returnGraceJob?.cancel()
        returnGraceJob =
            viewModelScope.launch {
                delay(RETURN_GRACE_MILLIS)
                val check = identityCheck
                if (isAwaitingWidgetReturn() && check != null) {
                    emitIdentityRun(
                        check,
                        VerificationStage.FAILED,
                        error = stringRes(R.string.increase_reputation_identity_error_no_return),
                    )
                }
            }
    }

    private fun isAwaitingWidgetReturn(): Boolean {
        val run = mutableState.value.run ?: return false
        return run.isIdentityCheck && run.stage == VerificationStage.VERIFYING
    }

    private fun collectIdentityRun(check: IdentityCheck, statuses: Flow<IdentityStatus>) {
        identityCheck = check
        widgetUrl = null
        returnGraceJob?.cancel()
        runJob =
            statuses
                .onEach { onIdentityStatus(check, it) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    Twig.warn(e) { "$check check failed" }
                    onIdentityStatus(check, IdentityStatus.Failed(IdentityFailure.Network))
                }.launchIn(viewModelScope)
    }

    private fun onIdentityStatus(check: IdentityCheck, status: IdentityStatus) {
        when (status) {
            IdentityStatus.Preparing -> {
                emitIdentityRun(check, VerificationStage.PREPARING)
            }

            is IdentityStatus.Ready -> {
                widgetUrl = status.widgetUrl
                emitIdentityRun(check, VerificationStage.READY)
            }

            // The driver waits on the redirect from the moment the session exists, before the
            // user has gone anywhere. The screen follows the tap instead, so "Open" stays offered
            // until it happens; a resumed run has no tap to wait for.
            IdentityStatus.Verifying -> {
                if (widgetOpened) emitIdentityRun(check, VerificationStage.VERIFYING)
            }

            IdentityStatus.Submitting -> {
                emitIdentityRun(check, VerificationStage.SUBMITTING)
            }

            is IdentityStatus.Done -> {
                onVerified(status.summary)
                emitIdentityRun(check, VerificationStage.DONE, summary = status.summary)
            }

            is IdentityStatus.Failed -> {
                if (status.reason == IdentityFailure.Cancelled) {
                    clearRun()
                } else {
                    emitIdentityRun(check, VerificationStage.FAILED, error = identityFailureMessage(status.reason))
                }
            }
        }
    }

    private fun emitIdentityRun(
        check: IdentityCheck,
        stage: VerificationStage,
        summary: ReputationSummary? = null,
        error: StringResource? = null,
    ) {
        if (stage in ACTIVE_STAGES) lastActiveStage = stage
        publish(
            VerificationRun(
                isIdentityCheck = true,
                stage = stage,
                steps = verificationSteps(stage, lastActiveStage, IDENTITY_STEP_LABELS),
                message = identityMessage(check, stage),
                error = error,
                launchUrl = widgetUrl,
                installIntentUrl = null,
                storeUrl = null,
                newPoints = summary?.points?.toString(),
                newBuyLimit = summary?.let(::newBuyLimit),
            ),
        )
    }

    private fun identityName(check: IdentityCheck): StringResource =
        stringRes(
            when (check) {
                IdentityCheck.Liveness -> R.string.increase_reputation_identity_liveness
                IdentityCheck.Passport -> R.string.increase_reputation_identity_passport
            },
        )

    private fun identityMessage(check: IdentityCheck, stage: VerificationStage): StringResource =
        when (stage) {
            VerificationStage.PREPARING -> stringRes(R.string.increase_reputation_preparing)
            VerificationStage.READY -> stringRes(R.string.increase_reputation_identity_ready, identityName(check))
            VerificationStage.VERIFYING -> stringRes(R.string.increase_reputation_identity_waiting)
            VerificationStage.SUBMITTING -> stringRes(R.string.increase_reputation_saving)
            VerificationStage.DONE -> stringRes(R.string.increase_reputation_identity_done, identityName(check))
            VerificationStage.FAILED -> stringRes(R.string.increase_reputation_failed)
        }

    private fun identityFailureMessage(failure: IdentityFailure): StringResource? =
        when (failure) {
            IdentityFailure.Unavailable -> stringRes(R.string.increase_reputation_identity_error_unavailable)
            IdentityFailure.NotPassed -> stringRes(R.string.increase_reputation_identity_error_not_passed)
            IdentityFailure.AlreadyClaimed -> stringRes(R.string.increase_reputation_identity_error_already_claimed)
            IdentityFailure.AlreadyVerified -> stringRes(R.string.increase_reputation_identity_error_already_verified)
            IdentityFailure.Expired -> stringRes(R.string.increase_reputation_identity_error_expired)
            IdentityFailure.Cancelled -> null
            IdentityFailure.Rejected -> stringRes(R.string.increase_reputation_identity_error_rejected)
            IdentityFailure.SponsorshipUnavailable -> stringRes(R.string.increase_reputation_error_gas)
            IdentityFailure.Network -> stringRes(R.string.increase_reputation_error_network)
        }

    private fun onVerified(read: ReputationSummary) {
        summary = read
        mutableState.update { it.copy(platforms = rows(read), identityChecks = identityRows(read)) }
    }

    private fun newBuyLimit(read: ReputationSummary): StringResource =
        stringRes(R.string.increase_reputation_new_limit, read.buyLimit.toDisplayString(stripTrailingZeros = true))

    private fun publish(run: VerificationRun) {
        mutableState.update {
            it.copy(
                run = run,
                primaryAction = primaryFor(run),
                secondaryAction = secondaryFor(run.stage),
            )
        }
    }

    private fun primaryFor(run: VerificationRun): ButtonState? {
        val open =
            stringRes(
                if (run.isIdentityCheck) {
                    R.string.increase_reputation_identity_open
                } else {
                    R.string.increase_reputation_open
                },
            )
        return when (run.stage) {
            VerificationStage.PREPARING -> {
                ButtonState(open, isEnabled = false)
            }

            // The view opens the link before invoking this: only it can reach an Intent.
            VerificationStage.READY -> {
                ButtonState(open, onClick = if (run.isIdentityCheck) ::onWidgetOpened else ::onReclaimLaunched)
            }

            VerificationStage.VERIFYING -> {
                ButtonState(open, isEnabled = false)
            }

            VerificationStage.SUBMITTING -> {
                ButtonState(stringRes(R.string.increase_reputation_saving_action), isEnabled = false)
            }

            VerificationStage.DONE -> {
                ButtonState(stringRes(R.string.increase_reputation_finish), onClick = ::onDone)
            }

            VerificationStage.FAILED -> {
                ButtonState(
                    stringRes(R.string.reputation_retry),
                    onClick = if (run.isIdentityCheck) ::onRetryIdentityRun else ::onDismissRun,
                )
            }
        }
    }

    private fun secondaryFor(stage: VerificationStage): ButtonState? =
        when (stage) {
            VerificationStage.READY, VerificationStage.VERIFYING -> {
                ButtonState(stringRes(R.string.increase_reputation_cancel), onClick = ::onCancelRun)
            }

            else -> {
                null
            }
        }

    /** Called once the Verifier has actually been opened; this is what stops the re-minting. */
    private fun onReclaimLaunched() {
        launchSignal?.markLaunched()
    }

    /** Called once the browser has actually been opened; only now is the user away in the check. */
    private fun onWidgetOpened() {
        widgetOpened = true
        identityCheck?.let { emitIdentityRun(it, VerificationStage.VERIFYING) }
    }

    private fun onRetryIdentityRun() {
        val check = identityCheck ?: return
        val previous = runJob
        previous?.cancel()
        runJob =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                runJob = null
                startIdentityRun(check)
            }
        runJob?.start()
    }

    private fun onCancelRun() = onDismissRun()

    /** Cancel browser authorization, but retain a redeemed result or a transaction awaiting a receipt. */
    private fun onDismissRun() {
        val previous = runJob
        val check = identityCheck
        previous?.cancel()
        runJob =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    previous?.cancelAndJoin()
                    check?.let { identityDriver.cancelWaiting(it, currency) }
                    clearRun()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception
                ) {
                    Twig.warn(e) { "Could not cancel pending identity check" }
                    check?.let { onIdentityStatus(it, IdentityStatus.Failed(IdentityFailure.Network)) }
                }
            }
        runJob?.start()
    }

    private fun clearRun() {
        runJob = null
        launchSignal = null
        returnSignal = null
        returnGraceJob?.cancel()
        identityCheck = null
        widgetUrl = null
        mutableState.update { it.copy(run = null, primaryAction = null, secondaryAction = null) }
    }

    private fun onDone() {
        navigationRouter.back()
    }

    private fun onBack() {
        if (mutableState.value.run != null) {
            onDismissRun()
        } else {
            navigationRouter.back()
        }
    }

    /** Random enough that a redirect from any other session, ours or not, fails the state check. */
    private fun nonce(): String = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes).toHex()

    private companion object {
        const val NONCE_BYTES = 16

        /** Long enough for a redirect that beat the app to the foreground to be collected. */
        const val RETURN_GRACE_MILLIS = 3_000L

        val ACTIVE_STAGES =
            setOf(VerificationStage.READY, VerificationStage.VERIFYING, VerificationStage.SUBMITTING)
    }
}

private val RECLAIM_STEP_LABELS =
    listOf(
        R.string.increase_reputation_step_open,
        R.string.increase_reputation_step_prove,
        R.string.increase_reputation_step_save,
    )

private val IDENTITY_STEP_LABELS =
    listOf(
        R.string.increase_reputation_identity_step_open,
        R.string.increase_reputation_identity_step_verify,
        R.string.increase_reputation_step_save,
    )

/** The first active indicator starts only after the user leaves Zapp to begin verification. */
internal fun verificationSteps(
    stage: VerificationStage,
    lastActiveStage: VerificationStage,
    labels: List<Int> = RECLAIM_STEP_LABELS,
): List<ZappStep> {
    val order = listOf(VerificationStage.READY, VerificationStage.VERIFYING, VerificationStage.SUBMITTING)
    val reached =
        when (stage) {
            VerificationStage.PREPARING, VerificationStage.READY -> -1
            VerificationStage.DONE -> order.size
            VerificationStage.FAILED -> order.indexOf(lastActiveStage)
            else -> order.indexOf(stage)
        }
    return labels.mapIndexed { index, label ->
        ZappStep(
            label = stringRes(label),
            status =
                when {
                    stage == VerificationStage.FAILED && index == reached -> ZappStepStatus.Failed
                    index < reached -> ZappStepStatus.Completed
                    index == reached -> ZappStepStatus.InProgress
                    else -> ZappStepStatus.Pending
                },
        )
    }
}
