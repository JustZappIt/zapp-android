package co.electriccoin.zcash.ui.screen.migration.complete

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.groupLce
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_DUST_THRESHOLD_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.HasLockedOrchardDustStorageProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.lockexplainer.MigrationLockExplainerArgs
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MigrationCompleteVM(
    private val migrationPlanRepository: MigrationPlanRepository,
    private val getOrchardBalance: GetOrchardBalanceUseCase,
    private val hasSeenMigrationCompleteStorageProvider: HasSeenMigrationCompleteStorageProvider,
    private val hasLockedOrchardDustStorageProvider: HasLockedOrchardDustStorageProvider,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val biometricRepository: BiometricRepository,
    private val proposalDataSource: ProposalDataSource,
    private val keystoneProposalRepository: KeystoneProposalRepository,
) : ViewModel() {

    private data class Summary(
        val totalTransferred: Long,
        val totalCount: Int,
        val firstAt: Long,
        val lastAt: Long,
        val dustZatoshi: Long,
    )

    // Cached across a failed-then-retried "Migrate anyway" attempt so a GrpcFailure retry (see
    // immediateSubmitFailureMessage's kdoc in MigrationReviewVM, the reference implementation this
    // mirrors) resubmits the exact same already-built/signed Proposal instead of re-proposing (which
    // could pick different notes) or re-prompting biometrics.
    private data class MigrateAnywayProposal(val proposal: Proposal, val amountZatoshi: Long)
    private var pendingMigrateAnywayProposal: MigrateAnywayProposal? = null

    private val loadLce = mutableLce<Summary>()
    private val migrateAnywayLce = mutableLce<Unit>()
    private val migrateAnywayFailure = MutableStateFlow<SubmitResult?>(null)

    init {
        loadLce.execute {
            val plan = migrationPlanRepository.load()
            // Whatever's still in the real Orchard balance once every transfer has sent is the
            // dust/residual left behind (below the migratable threshold, or an un-migrated
            // opt-in residual — either way, it's what's actually still sitting in Orchard).
            Summary(
                totalTransferred = plan?.transfers?.sumOf { it.amountZatoshi } ?: 0L,
                totalCount = plan?.totalCount ?: 0,
                firstAt = plan?.transfers?.minOfOrNull { it.scheduledAtEpochSeconds } ?: 0L,
                lastAt = plan?.transfers?.maxOfOrNull { it.scheduledAtEpochSeconds } ?: 0L,
                dustZatoshi = getOrchardBalance().value,
            )
        }
    }

    val state: StateFlow<LceState<MigrationCompleteState>> =
        combine(
            loadLce.state,
            hasLockedOrchardDustStorageProvider.observe(),
            migrateAnywayLce.state,
            migrateAnywayFailure,
        ) { lce, isLocked, migrateAnywayState, failure ->
            lce.success?.let { summary -> createState(summary, isLocked, migrateAnywayState.loading, failure) }
        }.withLce(groupLce(loadLce, migrateAnywayLce), errorStateMapper::mapToState).stateIn(this)

    private fun createState(
        summary: Summary,
        isLocked: Boolean,
        isMigrating: Boolean,
        failure: SubmitResult?,
    ): MigrationCompleteState =
        MigrationCompleteState(
            totalTransferred = stringRes(Zatoshi(summary.totalTransferred)),
            remainingDust = if (summary.dustZatoshi > 0L) stringRes(Zatoshi(summary.dustZatoshi)) else null,
            isDustLocked = isLocked,
            transfersProgress = stringRes("${summary.totalCount} of ${summary.totalCount} sent"),
            duration = stringRes(formatMigrationDuration(summary.lastAt - summary.firstAt)),
            isMigrating = isMigrating,
            onDone = ::onDone,
            onMigrateAnyway = { migrateAnywayLce.guardLoading(::onMigrateAnyway) },
            onLockBalance = ::onLockBalance,
            failureSheet = failure?.let {
                MigrationTransferFailureState(
                    message = migrateAnywaySubmitFailureMessage(it),
                    // Only a GrpcFailure is safely resubmittable — see MigrationReviewVM's
                    // identical reasoning for onRetry there.
                    onRetry = if (it is SubmitResult.GrpcFailure) {
                        {
                            migrateAnywayFailure.value = null
                            migrateAnywayLce.execute { retryMigrateAnyway() }
                        }
                    } else {
                        null
                    },
                    onDismiss = { migrateAnywayFailure.value = null },
                )
            },
        )

    private fun migrateAnywaySubmitFailureMessage(result: SubmitResult): String = when (result) {
        is SubmitResult.GrpcFailure -> "Couldn't reach the network. Check your connection and try again."
        is SubmitResult.Failure -> "The network rejected this transaction. Please contact support."
        is SubmitResult.Error -> "Something went wrong while sending. Please contact support."
        is SubmitResult.Partial -> "Some but not all of this transaction's parts were sent. Please contact support."
        is SubmitResult.Success -> error("migrateAnywaySubmitFailureMessage called with a Success result")
    }

    private fun onDone() {
        viewModelScope.launch {
            try {
                // Keystone-only auto-continuation (hot-wallet multi-run is deferred): if residual
                // Orchard balance is still above the real dust threshold (not just non-zero — a
                // multi-round campaign's per-round MigrationState.Complete doesn't distinguish
                // "genuinely done" from "this round's transfers are mined, more residual needs
                // another round"), clear the plan instead of marking "seen" — GetHomeMessageUseCase's
                // migrationMessageFor() then naturally re-evaluates to REQUIRED (plan == null) even
                // though the SDK's own MigrationState is still Complete (it only advances once the
                // next round is actually committed).
                val moreRoundsNeeded =
                    getSelectedWalletAccount() is KeystoneAccount &&
                        getOrchardBalance().value > MIGRATION_DUST_THRESHOLD_ZATOSHI
                if (moreRoundsNeeded) {
                    migrationPlanRepository.clear()
                } else {
                    // Marks the *banner's* seen-flag too, not a separate one — a user who's already
                    // been shown (and dismissed) this dedicated celebration screen doesn't also need
                    // the home banner nagging them afterwards; they're the same acknowledgment.
                    hasSeenMigrationCompleteStorageProvider.store(true)
                }
            } finally {
                // Guaranteed regardless of the above outcome (or an exception partway through it) —
                // navigation away from this screen must never be silently skipped.
                navigationRouter.backToRoot()
            }
        }
    }

    private fun onLockBalance() = navigationRouter.forward(MigrationLockExplainerArgs)

    // Mirrors MigrationReviewVM.confirmImmediate() — the canonical reference implementation for
    // sweeping a residual balance via the IMMEDIATE-mode send-max Proposal. Unlike Review, there's
    // no separate propose-then-confirm split here: propose and submit both happen in this single
    // user-triggered action, since this screen never shows a review step of its own for it.
    private fun onMigrateAnyway() = migrateAnywayLce.execute {
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
        val sdk = getOrchardMigrationSdk() ?: error("MigrationCompleteVM: no wallet available to propose")
        val amount = getOrchardBalance().value
        val proposal = sdk.proposeImmediateMigration()
        pendingMigrateAnywayProposal = MigrateAnywayProposal(proposal, amount)
        submitMigrateAnyway(proposal, amount)
    }

    private suspend fun retryMigrateAnyway() {
        val cached = pendingMigrateAnywayProposal ?: return
        submitMigrateAnyway(cached.proposal, cached.amountZatoshi)
    }

    private suspend fun submitMigrateAnyway(proposal: Proposal, amountZatoshi: Long) {
        if (getSelectedWalletAccount() is KeystoneAccount) {
            // Keystone can't sign in-process — adopt the already-built send-max proposal into the
            // app's existing generic external-signer pipeline exactly as an ordinary Keystone send
            // does (no migration-specific PCZT/QR machinery — one ordinary PCZT, same as any
            // regular Keystone send).
            keystoneProposalRepository.setMigrationSweepProposal(proposal, Zatoshi(amountZatoshi))
            // Required before navigating — SignKeystoneTransactionVM's QR encoder is built from the
            // already-created PCZT (createPCZTEncoder() reads KeystoneProposalRepository's cached
            // proposalPczt); it never calls createPCZTFromProposal() itself.
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
            else -> migrateAnywayFailure.value = result
        }
    }
}
