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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import xyz.justzappit.offramp.liveness.LivenessConfig
import xyz.justzappit.offramp.liveness.LivenessFailure
import xyz.justzappit.offramp.liveness.LivenessReader
import xyz.justzappit.offramp.liveness.LivenessReturn
import xyz.justzappit.offramp.liveness.LivenessReturnSignal
import xyz.justzappit.offramp.liveness.LivenessStanding
import xyz.justzappit.offramp.liveness.LivenessStatus
import xyz.justzappit.offramp.liveness.LivenessVerificationDriver
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reclaim.ReclaimFailure
import xyz.justzappit.offramp.reclaim.ReclaimLaunchSignal
import xyz.justzappit.offramp.reclaim.ReclaimStatus
import xyz.justzappit.offramp.reclaim.ReclaimVerificationDriver
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
 *
 * Two kinds of run share the screen. A social row hands the user to the Reclaim app and writes
 * the proof to the ReputationManager; the selfie row hands them to a browser widget and writes
 * the attestation to Zapp's own integrator. Same stages, same buttons, different words.
 */
@Suppress("TooManyFunctions")
internal class IncreaseReputationVM(
    args: IncreaseReputationArgs,
    private val navigationRouter: NavigationRouter,
    private val accountProvider: SmartOfframpAccountProvider,
    private val reputationReader: ReputationReader,
    private val verificationDriver: ReclaimVerificationDriver,
    private val livenessConfig: LivenessConfig,
    private val livenessReader: LivenessReader,
    private val livenessDriver: LivenessVerificationDriver,
    private val livenessReturns: LivenessReturnInbox,
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
    private var returnSignal: LivenessReturnSignal? = null
    private var widgetUrl: String? = null
    private var widgetOpened = false
    private var lastActiveStage = VerificationStage.READY

    private val mutableState =
        MutableStateFlow(
            IncreaseReputationState(
                isLoading = true,
                platforms = emptyList(),
                liveness = null,
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
        // through its signal, a cold-started screen resumes from it, and one that arrives after
        // the user cancelled still finishes the check they went on to complete.
        if (livenessConfig.enabled) {
            livenessReturns.returns
                .filterNotNull()
                .onEach { onLivenessReturn() }
                .launchIn(viewModelScope)
        }
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        mutableState.update { it.copy(isLoading = true, error = null) }
        loadJob =
            viewModelScope.launch {
                try {
                    val address = accountProvider.resolve().address
                    val (read, standing) =
                        coroutineScope {
                            val reputation = async { reputationReader.read(address, currency) }
                            val liveness = async { if (livenessConfig.enabled) livenessReader.read(address) else null }
                            reputation.await() to liveness.await()
                        }
                    summary = read
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            platforms = rows(read),
                            liveness = livenessRow(standing),
                        )
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
                VerifiableRow(
                    platform = platform,
                    name = platform.onChainName,
                    reward =
                        if (platform in read.verified) {
                            stringRes(R.string.increase_reputation_verified)
                        } else {
                            stringRes(R.string.increase_reputation_reward, read.award(platform).toString())
                        },
                    limitGain =
                        read.limitGainFor(platform)?.let {
                            stringRes(
                                R.string.increase_reputation_limit_gain,
                                it.toDisplayString(stripTrailingZeros = true),
                            )
                        },
                    requirement =
                        if (platform.requiresMatureAccount && platform !in read.verified) {
                            stringRes(R.string.increase_reputation_age_requirement)
                        } else {
                            null
                        },
                    isVerified = platform in read.verified,
                    // Verified rows stay listed and inert: hiding one reads as a bug, and the user
                    // has no other place that says the account is already spent.
                    onClick = { if (platform !in read.verified) startRun(platform) },
                )
            }

    private fun livenessRow(standing: LivenessStanding?): LivenessRow? =
        standing?.let {
            LivenessRow(
                reward =
                    if (it.isVerified) {
                        stringRes(R.string.reputation_amount_usd, it.limit.usd())
                    } else {
                        stringRes(R.string.increase_reputation_liveness_reward, it.tierCap.usd())
                    },
                isVerified = it.isVerified,
                onClick = { if (!it.isVerified) startLivenessRun() },
            )
        }

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
                summary = status.summary
                emitRun(platform, VerificationStage.DONE, summary = status.summary)
                mutableState.update { it.copy(platforms = rows(status.summary)) }
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
                platform = platform,
                name = stringRes(platform.onChainName),
                stage = stage,
                steps = verificationSteps(stage, lastActiveStage),
                message = message(platform, stage),
                error = failure?.let(::failureMessage),
                launchUrl = ready?.requestUrl,
                installIntentUrl = ready?.installIntentUrl,
                storeUrl = ready?.storeUrl,
                newPoints = summary?.points?.toString(),
                newBuyLimit =
                    summary?.let {
                        stringRes(R.string.increase_reputation_new_limit, it.buyLimit.usd())
                    },
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

    private fun startLivenessRun() {
        if (runJob?.isActive == true) return
        val signal = LivenessReturnSignal()
        returnSignal = signal
        widgetUrl = null
        widgetOpened = false
        collectLivenessRun(livenessDriver.verify(currency, nonce(), signal))
    }

    /** Finishes a check whose redirect outlived the run that started it — a cold start, or a cancel. */
    private fun resumeLivenessRun(ret: LivenessReturn) {
        if (runJob?.isActive == true) return
        returnSignal = null
        widgetUrl = null
        widgetOpened = true
        collectLivenessRun(livenessDriver.resume(ret))
    }

    private fun onLivenessReturn() {
        val ret = livenessReturns.take() ?: return
        val signal = returnSignal
        when {
            signal != null && runJob?.isActive == true -> signal.deliver(ret)

            runJob?.isActive != true -> resumeLivenessRun(ret)

            // A Reclaim run owns the screen. The check is not lost: the user can take it again.
            else -> Unit
        }
    }

    private fun collectLivenessRun(statuses: Flow<LivenessStatus>) {
        runJob =
            statuses
                .onEach(::onLivenessStatus)
                .catch { e ->
                    if (e is CancellationException) throw e
                    Twig.warn(e) { "Selfie check failed" }
                    onLivenessStatus(LivenessStatus.Failed(LivenessFailure.Network))
                }.launchIn(viewModelScope)
    }

    private fun onLivenessStatus(status: LivenessStatus) {
        when (status) {
            LivenessStatus.Preparing -> {
                emitLivenessRun(VerificationStage.PREPARING)
            }

            is LivenessStatus.Ready -> {
                widgetUrl = status.widgetUrl
                emitLivenessRun(VerificationStage.READY)
            }

            // The driver waits on the redirect from the moment the session exists, before the
            // user has gone anywhere. The screen follows the tap instead, so "Open" stays offered
            // until it happens; a resumed run has no tap to wait for.
            LivenessStatus.Verifying -> {
                if (widgetOpened) emitLivenessRun(VerificationStage.VERIFYING)
            }

            LivenessStatus.Submitting -> {
                emitLivenessRun(VerificationStage.SUBMITTING)
            }

            is LivenessStatus.Done -> {
                emitLivenessRun(VerificationStage.DONE, standing = status.standing)
                mutableState.update { it.copy(liveness = livenessRow(status.standing)) }
            }

            is LivenessStatus.Failed -> {
                if (status.reason == LivenessFailure.Cancelled) {
                    clearRun()
                } else {
                    emitLivenessRun(VerificationStage.FAILED, failure = status.reason)
                }
            }
        }
    }

    private fun emitLivenessRun(
        stage: VerificationStage,
        standing: LivenessStanding? = null,
        failure: LivenessFailure? = null,
    ) {
        if (stage in ACTIVE_STAGES) lastActiveStage = stage
        publish(
            VerificationRun(
                platform = null,
                name = stringRes(R.string.increase_reputation_liveness_row),
                stage = stage,
                steps = verificationSteps(stage, lastActiveStage, LIVENESS_STEP_LABELS),
                message = livenessMessage(stage),
                error = failure?.let(::livenessFailureMessage),
                launchUrl = widgetUrl,
                installIntentUrl = null,
                storeUrl = null,
                newBuyLimit =
                    standing?.let {
                        stringRes(R.string.increase_reputation_new_limit, it.limit.usd())
                    },
            ),
        )
    }

    private fun livenessMessage(stage: VerificationStage): StringResource =
        when (stage) {
            VerificationStage.PREPARING -> stringRes(R.string.increase_reputation_preparing)
            VerificationStage.READY -> stringRes(R.string.increase_reputation_liveness_ready)
            VerificationStage.VERIFYING -> stringRes(R.string.increase_reputation_liveness_waiting)
            VerificationStage.SUBMITTING -> stringRes(R.string.increase_reputation_saving)
            VerificationStage.DONE -> stringRes(R.string.increase_reputation_liveness_done)
            VerificationStage.FAILED -> stringRes(R.string.increase_reputation_failed)
        }

    private fun livenessFailureMessage(failure: LivenessFailure): StringResource? =
        when (failure) {
            LivenessFailure.NotConfigured -> stringRes(R.string.increase_reputation_liveness_error_unavailable)
            LivenessFailure.NotLive -> stringRes(R.string.increase_reputation_liveness_error_not_live)
            LivenessFailure.AlreadyClaimed -> stringRes(R.string.increase_reputation_liveness_error_already_claimed)
            LivenessFailure.Expired -> stringRes(R.string.increase_reputation_liveness_error_expired)
            LivenessFailure.Cancelled -> null
            LivenessFailure.Rejected -> stringRes(R.string.increase_reputation_liveness_error_rejected)
            LivenessFailure.SponsorshipUnavailable -> stringRes(R.string.increase_reputation_error_gas)
            LivenessFailure.Network -> stringRes(R.string.increase_reputation_error_network)
        }

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
        val isLiveness = run.platform == null
        val open =
            stringRes(
                if (isLiveness) R.string.increase_reputation_liveness_open else R.string.increase_reputation_open,
            )
        return when (run.stage) {
            VerificationStage.PREPARING -> {
                ButtonState(open, isEnabled = false)
            }

            // The view opens the link before invoking this: only it can reach an Intent.
            VerificationStage.READY -> {
                ButtonState(open, onClick = if (isLiveness) ::onWidgetOpened else ::onReclaimLaunched)
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
                ButtonState(stringRes(R.string.reputation_retry), onClick = ::onDismissRun)
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
        emitLivenessRun(VerificationStage.VERIFYING)
    }

    /**
     * Cancelling leaves the Reclaim session, or the widget session, to expire on its own. It is
     * never surfaced later as an error — the user chose to stop.
     */
    private fun onCancelRun() {
        runJob?.cancel()
        onDismissRun()
    }

    private fun onDismissRun() {
        runJob?.cancel()
        clearRun()
    }

    private fun clearRun() {
        runJob = null
        launchSignal = null
        returnSignal = null
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

    private fun Usdc6.usd(): String = toDisplayString(stripTrailingZeros = true)

    private companion object {
        const val NONCE_BYTES = 16

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

private val LIVENESS_STEP_LABELS =
    listOf(
        R.string.increase_reputation_liveness_step_open,
        R.string.increase_reputation_liveness_step_selfie,
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
