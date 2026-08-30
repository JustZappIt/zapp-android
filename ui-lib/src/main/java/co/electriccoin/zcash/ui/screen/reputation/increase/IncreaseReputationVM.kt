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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reclaim.ReclaimFailure
import xyz.justzappit.offramp.reclaim.ReclaimLaunchSignal
import xyz.justzappit.offramp.reclaim.ReclaimStatus
import xyz.justzappit.offramp.reclaim.ReclaimVerificationDriver
import xyz.justzappit.offramp.reputation.ReputationReader
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.SocialPlatform

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
) : ViewModel() {
    private val currency = args.currency
    private var summary: ReputationSummary? = null
    private var loadJob: Job? = null
    private var runJob: Job? = null
    private var launchSignal: ReclaimLaunchSignal? = null
    private var ready: ReclaimStatus.Ready? = null
    private var lastActiveStage = VerificationStage.READY

    private val mutableState =
        MutableStateFlow(
            IncreaseReputationState(
                isLoading = true,
                platforms = emptyList(),
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
                        it.copy(isLoading = false, error = null, platforms = rows(read))
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
        runJob =
            verificationDriver
                .verify(platform, currency, signal)
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
        val run =
            VerificationRun(
                platform = platform,
                name = platform.onChainName,
                stage = stage,
                steps = steps(stage),
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
            )
        mutableState.update {
            it.copy(
                run = run,
                primaryAction = primaryFor(stage),
                secondaryAction = secondaryFor(stage),
            )
        }
    }

    private fun steps(stage: VerificationStage): List<ZappStep> {
        val order = listOf(VerificationStage.READY, VerificationStage.VERIFYING, VerificationStage.SUBMITTING)
        val labels =
            listOf(
                R.string.increase_reputation_step_open,
                R.string.increase_reputation_step_prove,
                R.string.increase_reputation_step_save,
            )
        val reached =
            when (stage) {
                VerificationStage.PREPARING -> -1
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

    private fun primaryFor(stage: VerificationStage): ButtonState? =
        when (stage) {
            VerificationStage.PREPARING -> {
                ButtonState(stringRes(R.string.increase_reputation_open), isEnabled = false)
            }

            // The view opens the link before invoking this: only it can reach an Intent.
            VerificationStage.READY -> {
                ButtonState(stringRes(R.string.increase_reputation_open), onClick = ::onReclaimLaunched)
            }

            VerificationStage.VERIFYING -> {
                ButtonState(stringRes(R.string.increase_reputation_open), isEnabled = false)
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

    /**
     * Cancelling leaves the Reclaim session to expire on its own. It is never surfaced later as an
     * error — the user chose to stop.
     */
    private fun onCancelRun() {
        runJob?.cancel()
        onDismissRun()
    }

    private fun onDismissRun() {
        runJob?.cancel()
        runJob = null
        launchSignal = null
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

    private fun Usdc6.usd(): String = toDisplayString(stripTrailingZeros = true)

    private companion object {
        val ACTIVE_STAGES =
            setOf(VerificationStage.READY, VerificationStage.VERIFYING, VerificationStage.SUBMITTING)
    }
}
