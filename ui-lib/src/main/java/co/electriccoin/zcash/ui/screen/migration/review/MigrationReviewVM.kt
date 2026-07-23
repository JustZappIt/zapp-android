package co.electriccoin.zcash.ui.screen.migration.review

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MigrationKeystoneRound
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.estimatedSecondsBetweenHeights
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.groupLce
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignArgs
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.MathContext

class MigrationReviewVM(
    private val args: MigrationReviewArgs,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val pendingMigrationScheduleRepository: PendingMigrationScheduleRepository,
    private val restartMigrationScheduleRepository: RestartMigrationScheduleRepository,
    private val finalizeMigrationSchedule: FinalizeMigrationScheduleUseCase,
    private val navigationRouter: NavigationRouter,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getOrchardBalance: GetOrchardBalanceUseCase,
    private val errorStateMapper: ErrorMapperUseCase,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val biometricRepository: BiometricRepository,
    private val proposalDataSource: ProposalDataSource,
    private val keystoneProposalRepository: KeystoneProposalRepository,
) : ViewModel() {

    // proposeImmediateMigration() now returns an ordinary send-max Proposal (bypassing the
    // migration engine entirely — see OrchardMigrationSdk's kdoc), which carries no amount or
    // destination of its own (only totalFeeRequired()/transactionCount()); the amount shown is
    // this account's Orchard balance at propose time (the whole point of a send-max sweep).
    private sealed class ReviewProposal {
        data class Automatic(val schedule: MigrationSchedule, val keystoneRunCount: Int?) : ReviewProposal()
        data class Immediate(val proposal: Proposal, val amountZatoshi: Long) : ReviewProposal()
    }

    private val proposeLce = mutableLce<ReviewProposal>()
    private val confirmLce = mutableLce<Unit>()
    private val isKeystoneAccount = getSelectedWalletAccount.observe().map { it is KeystoneAccount }
    private val failure = MutableStateFlow<TransferResult?>(null)
    private val immediateFailure = MutableStateFlow<SubmitResult?>(null)
    private val failures = combine(failure, immediateFailure, ::Pair)

    init {
        proposeLce.execute {
            val sdk = getOrchardMigrationSdk() ?: error("MigrationReviewVM: no wallet available to propose")
            when (args.mode) {
                MigrationMode.IMMEDIATE -> {
                    val amount = getOrchardBalance().value
                    ReviewProposal.Immediate(sdk.proposeImmediateMigration(), amount)
                }
                MigrationMode.AUTOMATIC -> {
                    // If MigrationTransferInvalidVM.onContinue() already obtained a fresh schedule
                    // via restartCurrentMigrationStep() — whose own doc requires that returned
                    // schedule to go through this normal confirmation flow rather than being
                    // silently re-proposed — reuse that exact schedule instead of calling
                    // proposeMigrationTransfers() again (see RestartMigrationScheduleRepository's
                    // doc: the two calls compute independent guesses over the same balance that
                    // aren't guaranteed to agree). Falls back to a fresh proposal for every
                    // ordinary, non-recovery entry into this screen.
                    val schedule = restartMigrationScheduleRepository.consume() ?: sdk.proposeMigrationTransfers()
                    // IMMEDIATE has no Keystone branch at all (a documented pre-existing gap —
                    // see MigrationReviewVM.confirmAutomatic()'s Keystone check below), so round
                    // display is AUTOMATIC-only. Stateless preview, called fresh on every Review
                    // entry — never cached.
                    val keystoneRunCount = if (getSelectedWalletAccount() is KeystoneAccount) {
                        sdk.estimateMigrationRunCount()
                    } else {
                        null
                    }
                    ReviewProposal.Automatic(schedule, keystoneRunCount)
                }
            }
        }
    }

    val state: StateFlow<LceState<MigrationReviewState>> =
        combine(
            proposeLce.state, exchangeRateRepository.state, isKeystoneAccount, failures, confirmLce.state
        ) { lce, rate, isKeystone, (f, imf), confirmState ->
            lce.success?.let { proposal -> createState(proposal, confirmState.loading, rate, isKeystone, f, imf) }
        }.withLce(groupLce(proposeLce, confirmLce), errorStateMapper::mapToState)
            .stateIn(this)

    private fun createState(
        proposal: ReviewProposal,
        isConfirming: Boolean,
        exchangeRateState: ExchangeRateState,
        isKeystone: Boolean,
        failureResult: TransferResult?,
        immediateFailureResult: SubmitResult?,
    ): MigrationReviewState = when (proposal) {
        is ReviewProposal.Automatic -> createAutomaticState(proposal, isConfirming, exchangeRateState, isKeystone, failureResult)
        is ReviewProposal.Immediate -> createImmediateState(proposal, isConfirming, exchangeRateState, immediateFailureResult)
    }

    private fun createAutomaticState(
        proposal: ReviewProposal.Automatic,
        isConfirming: Boolean,
        exchangeRateState: ExchangeRateState,
        isKeystone: Boolean,
        failureResult: TransferResult?,
    ): MigrationReviewState {
        val sched = proposal.schedule
        val total = sched.transfers.sumOf { it.amountZatoshi }
        // From the plan's "now" reference (anchorHeight — every transfer shares the same plan-time
        // tip) to the LAST transfer's height, matching scheduledLabel()'s per-transfer calculation
        // below and MigrationScheduledVM/MigrationProgressVM's createdAt-to-last-scheduled span —
        // NOT firstAtHeight-to-lastAtHeight, which omits the wait before the first transfer and
        // previously made this summary disagree with the per-transfer rows and the other two
        // migration screens (confirmed live: header claimed a shorter span than the last
        // transfer's own "due in ~Nh" label showed).
        val anchorHeight = sched.transfers.minOfOrNull { it.anchorHeight } ?: 0L
        val lastAtHeight = sched.transfers.maxOfOrNull { it.nextExecutableAfterHeight } ?: 0L
        val spanSeconds = estimatedSecondsBetweenHeights(anchorHeight, lastAtHeight)
        return MigrationReviewState(
            mode = args.mode,
            totalAmount = stringRes(Zatoshi(total)),
            totalFiatAmount = fiatAmount(Zatoshi(total), exchangeRateState),
            estimatedDuration = stringRes(formatMigrationDuration(spanSeconds)),
            transfers = sched.transfers.mapIndexed { i, t ->
                MigrationReviewTransferState(
                    index = i + 1,
                    totalCount = sched.transfers.size,
                    amount = stringRes(Zatoshi(t.amountZatoshi)),
                    fiatAmount = fiatAmount(Zatoshi(t.amountZatoshi), exchangeRateState),
                    scheduledLabel = scheduledLabel(t),
                )
            },
            isKeystone = isKeystone,
            keystoneRound = proposal.keystoneRunCount?.takeIf { it > 1 }?.let { MigrationKeystoneRound(current = 1, total = it) },
            isConfirming = isConfirming,
            onConfirm = { proposeLce.guardLoading { onConfirmAutomatic(sched) } },
            onBack = ::onBack,
            failureSheet = failureResult?.let {
                MigrationTransferFailureState(
                    message = migrationFailureMessage(it),
                    onRetry = { failure.value = null; proposeLce.guardLoading { onConfirmAutomatic(sched) } },
                    onDismiss = { failure.value = null },
                )
            },
        )
    }

    // proposeImmediateMigration()'s raw send-max Proposal carries no destination-facing
    // "list of transfers" the way a MigrationSchedule does — this renders it as a single
    // synthetic row so the (shared) review layout still has something to show, using the real
    // fee from Proposal.totalFeeRequired() instead of AUTOMATIC's placeholder.
    private fun createImmediateState(
        proposal: ReviewProposal.Immediate,
        isConfirming: Boolean,
        exchangeRateState: ExchangeRateState,
        immediateFailureResult: SubmitResult?,
    ): MigrationReviewState {
        val fee = proposal.proposal.totalFeeRequired()
        return MigrationReviewState(
            mode = args.mode,
            totalAmount = stringRes(Zatoshi(proposal.amountZatoshi)),
            totalFiatAmount = fiatAmount(Zatoshi(proposal.amountZatoshi), exchangeRateState),
            estimatedDuration = stringRes(formatMigrationDuration(0L)),
            transfers = listOf(
                MigrationReviewTransferState(
                    index = 1,
                    totalCount = 1,
                    amount = stringRes(Zatoshi(proposal.amountZatoshi)),
                    fiatAmount = fiatAmount(Zatoshi(proposal.amountZatoshi), exchangeRateState),
                    scheduledLabel = stringRes("Send immediately"),
                )
            ),
            fee = stringRes(fee),
            isConfirming = isConfirming,
            onConfirm = { onConfirmImmediate(proposal.proposal, proposal.amountZatoshi) },
            onBack = ::onBack,
            failureSheet = immediateFailureResult?.let {
                MigrationTransferFailureState(
                    message = immediateSubmitFailureMessage(it),
                    // Only a GrpcFailure is safely resubmittable — resending the identical signed
                    // Proposal after a genuine Failure/Error/Partial would either re-fail
                    // identically or, for Partial, risk re-broadcasting already-sent internal
                    // transactions. Omitting the retry button (rather than silently wiring it to
                    // "go back", which the shared bottom sheet used to do for such cases) keeps the
                    // sheet from lying about what its button does.
                    onRetry = if (it is SubmitResult.GrpcFailure) {
                        {
                            immediateFailure.value = null
                            confirmLce.execute { confirmImmediate(proposal.proposal, proposal.amountZatoshi) }
                        }
                    } else {
                        null
                    },
                    onDismiss = { immediateFailure.value = null },
                )
            },
        )
    }

    private fun immediateSubmitFailureMessage(result: SubmitResult): String = when (result) {
        is SubmitResult.GrpcFailure -> "Couldn't reach the network. Check your connection and try again."
        is SubmitResult.Failure -> "The network rejected this transaction. Please contact support."
        is SubmitResult.Error -> "Something went wrong while sending. Please contact support."
        is SubmitResult.Partial -> "Some but not all of this transaction's parts were sent. Please contact support."
        is SubmitResult.Success -> error("immediateSubmitFailureMessage called with a Success result")
    }

    private fun fiatAmount(zatoshi: Zatoshi, exchangeRateState: ExchangeRateState): StringResource? {
        val data = exchangeRateState as? ExchangeRateState.Data ?: return null
        val conversion = data.currencyConversion ?: return null
        return stringResByDynamicCurrencyNumber(
            amount =
                zatoshi
                    .convertZatoshiToZec()
                    .multiply(BigDecimal(conversion.priceOfZec), MathContext.DECIMAL128),
            ticker = data.expectedCurrency.symbol,
        )
    }

    private fun onConfirmAutomatic(sched: MigrationSchedule) =
        confirmLce.execute {
            try {
                biometricRepository.requestBiometrics(
                    request =
                        BiometricRequest(
                            message =
                                stringRes(
                                    R.string.authentication_system_ui_subtitle,
                                    stringRes(R.string.authentication_use_case_send_funds)
                                )
                        )
                )
            } catch (_: BiometricsFailureException) {
                return@execute
            } catch (_: BiometricsCancelledException) {
                return@execute
            }
            confirmAutomatic(sched)
        }

    private suspend fun confirmAutomatic(sched: MigrationSchedule) {
        if (getSelectedWalletAccount() is KeystoneAccount) {
            // Keystone can't sign in-process — hand the unsigned schedule off to the QR
            // sign/scan detour; FinalizeMigrationScheduleUseCase runs after a successful scan
            // instead (MigrationKeystoneScanVM), not here.
            pendingMigrationScheduleRepository.set(sched)
            navigationRouter.forward(MigrationKeystoneSignArgs(mode = args.mode))
            return
        }
        val sdk = getOrchardMigrationSdk() ?: error("MigrationReviewVM: no wallet available to sign")
        // Note-split is the first step of this confirm action (design spec §7) — a schedule with
        // more than one denomination proposed against raw, unsplit notes exhausts the wallet's
        // balance on the first transfer, leaving every subsequent transfer InsufficientFunds. Per
        // spec §3 the split is a fully shielded self-send and needs no sync-decoupling delay, so
        // proceeding straight to signAndStoreMigrationSchedule below is safe. Under the crate's
        // sign-now/prove-later pipeline that call now signs successfully immediately even though
        // the split's own output isn't mined/witnessed yet.
        //
        // `sched` was proposed at screen init, before any split — proposeMigrationTransfers()'s
        // denomination guess and prepareNoteSplit()'s own (independent) guess over the same
        // balance are not guaranteed to agree. Reusing the stale `sched` here could schedule a
        // transfer for a denomination the split never actually mints, which then silently falls
        // back to an unrelated already-existing note — one the split's own "sweep everything"
        // construction may already be consuming as one of its own inputs (a real double-spend
        // found live on testnet). Re-deriving the schedule from the split's own realized output
        // plan makes every crossing value provably match a note this split actually produces.
        val scheduleToSign = if (sdk.isNoteSplitNeeded()) {
            val proposal = sdk.prepareNoteSplit()
            val splitResult = sdk.submitNoteSplit(proposal, zashiSpendingKeyDataSource.getZashiSpendingKey())
            if (splitResult !is TransferResult.Success) {
                failure.value = splitResult
                return
            }
            sdk.proposeMigrationTransfersFromSplit(proposal)
        } else {
            sched
        }
        sdk.signAndStoreMigrationSchedule(scheduleToSign, zashiSpendingKeyDataSource.getZashiSpendingKey())
        finalizeMigrationSchedule(scheduleToSign, args.mode)
    }

    private fun onConfirmImmediate(proposal: Proposal, amountZatoshi: Long) =
        confirmLce.execute {
            try {
                biometricRepository.requestBiometrics(
                    request =
                        BiometricRequest(
                            message =
                                stringRes(
                                    R.string.authentication_system_ui_subtitle,
                                    stringRes(R.string.authentication_use_case_send_funds)
                                )
                        )
                )
            } catch (_: BiometricsFailureException) {
                return@execute
            } catch (_: BiometricsCancelledException) {
                return@execute
            }
            confirmImmediate(proposal, amountZatoshi)
        }

    private suspend fun confirmImmediate(proposal: Proposal, amountZatoshi: Long) {
        if (getSelectedWalletAccount() is KeystoneAccount) {
            // Keystone can't sign in-process — adopt the already-built send-max proposal into the
            // app's existing generic external-signer pipeline exactly as an ordinary Keystone send
            // does (no migration-specific PCZT/QR machinery — one ordinary PCZT, same as any
            // regular Keystone send).
            keystoneProposalRepository.setMigrationSweepProposal(proposal, Zatoshi(amountZatoshi))
            // Required before navigating — SignKeystoneTransactionVM's QR encoder is built from
            // the already-created PCZT (createPCZTEncoder() reads KeystoneProposalRepository's
            // cached proposalPczt); it never calls createPCZTFromProposal() itself. Every other
            // Keystone entry point (CreateProposalUseCase, ShieldFundsUseCase, etc.) does this
            // same two-call sequence before forwarding.
            keystoneProposalRepository.createPCZTFromProposal()
            navigationRouter.forward(SignKeystoneTransactionArgs)
            return
        }
        val usk = zashiSpendingKeyDataSource.getZashiSpendingKey()
        val result = withContext(NonCancellable) {
            proposalDataSource.submitTransaction(proposal, usk)
        }
        when (result) {
            is SubmitResult.Success -> navigationRouter.forward(MigrationSuccessArgs(result.txIds.lastOrNull()))
            else -> immediateFailure.value = result
        }
    }

    private fun onBack() = proposeLce.guardLoading { navigationRouter.back() }

    // Only ever called for AUTOMATIC (createImmediateState hardcodes its own single-row label
    // instead — a raw send-max Proposal carries no per-transfer schedule to derive one from).
    private fun scheduledLabel(t: TransferProposal): StringResource {
        val secondsUntil = estimatedSecondsBetweenHeights(t.anchorHeight, t.nextExecutableAfterHeight)
        return when {
            secondsUntil <= 0 -> stringRes("Ready now")
            secondsUntil < 3600 -> stringRes("~${(secondsUntil / 60).coerceAtLeast(1)} min")
            else -> stringRes("~${secondsUntil / 3600} hours")
        }
    }
}
