package co.electriccoin.zcash.ui.screen.migration.review

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MigrationDeliveryMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.estimatedSecondsBetweenHeights
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.migration.toMigrationPlan
import co.electriccoin.zcash.ui.common.model.groupLce
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignArgs
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.MathContext

class MigrationReviewVM(
    private val args: MigrationReviewArgs,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val pendingMigrationScheduleRepository: PendingMigrationScheduleRepository,
    private val finalizeMigrationSchedule: FinalizeMigrationScheduleUseCase,
    private val navigationRouter: NavigationRouter,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val errorStateMapper: ErrorMapperUseCase,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
) : ViewModel() {

    private val proposeLce = mutableLce<MigrationSchedule>()
    private val confirmLce = mutableLce<Unit>()
    private val isKeystoneAccount = getSelectedWalletAccount.observe().map { it is KeystoneAccount }
    private val failure = MutableStateFlow<TransferResult?>(null)

    init {
        proposeLce.execute {
            val sdk = getOrchardMigrationSdk() ?: error("MigrationReviewVM: no wallet available to propose")
            when (args.mode) {
                MigrationMode.IMMEDIATE -> sdk.proposeImmediateMigration()
                MigrationMode.AUTOMATIC -> sdk.proposeMigrationTransfers()
            }
        }
    }

    val state: StateFlow<LceState<MigrationReviewState>> =
        combine(
            proposeLce.state, exchangeRateRepository.state, isKeystoneAccount, failure, confirmLce.state
        ) { lce, rate, isKeystone, f, confirmState ->
            lce.success?.let { sched -> createState(sched, confirmState.loading, rate, isKeystone, f) }
        }.withLce(groupLce(proposeLce, confirmLce), errorStateMapper::mapToState)
            .stateIn(this)

    private fun createState(
        sched: MigrationSchedule,
        isConfirming: Boolean,
        exchangeRateState: ExchangeRateState,
        isKeystone: Boolean,
        failureResult: TransferResult?,
    ): MigrationReviewState {
        val total = sched.transfers.sumOf { it.amountZatoshi }
        val firstAtHeight = sched.transfers.minOfOrNull { it.nextExecutableAfterHeight } ?: 0L
        val lastAtHeight = sched.transfers.maxOfOrNull { it.nextExecutableAfterHeight } ?: 0L
        val spanSeconds = estimatedSecondsBetweenHeights(firstAtHeight, lastAtHeight)
        return MigrationReviewState(
            mode = args.mode,
            totalAmount = stringRes(Zatoshi(total)),
            estimatedDuration = stringRes(formatMigrationDuration(spanSeconds)),
            transfers = sched.transfers.mapIndexed { i, t ->
                MigrationReviewTransferState(
                    index = i + 1,
                    totalCount = sched.transfers.size,
                    amount = stringRes(Zatoshi(t.amountZatoshi)),
                    fiatAmount = fiatAmount(Zatoshi(t.amountZatoshi), exchangeRateState),
                    scheduledLabel = scheduledLabel(t, args.mode),
                )
            },
            isKeystone = isKeystone,
            // TransferProposal has no fee field (SDK model, out of scope to change here) — mirror
            // the mock fee magnitude OrchardMigrationSdkMock.submitNoteSplit() already uses for a
            // similar placeholder network fee shown in the UI.
            fee = if (args.mode == MigrationMode.IMMEDIATE) stringRes(Zatoshi(IMMEDIATE_MODE_MOCK_FEE_ZATOSHI)) else null,
            isConfirming = isConfirming,
            onConfirm = { proposeLce.guardLoading { onConfirm(sched) } },
            onBack = ::onBack,
            failureSheet = failureResult?.let {
                MigrationTransferFailureState(
                    message = migrationFailureMessage(it),
                    onRetry = { failure.value = null; proposeLce.guardLoading { onConfirm(sched) } },
                    onDismiss = { failure.value = null },
                )
            },
        )
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

    private fun onConfirm(sched: MigrationSchedule) =
        confirmLce.execute {
            when (args.mode) {
                // Immediate mode broadcasts synchronously in the foreground on the Sending
                // screen — no WorkManager job needed (and scheduling one here would race it).
                //
                // Unlike confirmAutomatic(), this path doesn't branch on KeystoneAccount — a
                // pre-existing gap, not introduced here. Signing always uses the Zashi account's
                // own key until immediate mode gets the same Keystone detour.
                MigrationMode.IMMEDIATE -> {
                    val sdk = getOrchardMigrationSdk() ?: error("MigrationReviewVM: no wallet available to sign")
                    sdk.signAndStoreMigrationSchedule(
                        sched,
                        zashiSpendingKeyDataSource.getZashiSpendingKey(),
                    )
                    migrationPlanRepository.save(sched.toMigrationPlan(args.mode, MigrationDeliveryMode.SCHEDULED))
                    navigationRouter.forward(MigrationSendingArgs)
                }
                MigrationMode.AUTOMATIC -> confirmAutomatic(sched)
            }
        }

    private suspend fun confirmAutomatic(sched: MigrationSchedule) {
        if (getSelectedWalletAccount() is KeystoneAccount) {
            // Keystone can't sign in-process — hand the unsigned schedule off to the QR
            // sign/scan detour; FinalizeMigrationScheduleUseCase runs after a successful scan
            // instead (MigrationKeystoneScanVM), not here.
            pendingMigrationScheduleRepository.set(sched)
            navigationRouter.forward(
                MigrationKeystoneSignArgs(mode = args.mode, backgroundAvailable = args.backgroundAvailable)
            )
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
        if (sdk.isNoteSplitNeeded()) {
            val proposal = sdk.prepareNoteSplit()
            val splitResult = sdk.submitNoteSplit(proposal, zashiSpendingKeyDataSource.getZashiSpendingKey())
            if (splitResult !is TransferResult.Success) {
                failure.value = splitResult
                return
            }
        }
        sdk.signAndStoreMigrationSchedule(sched, zashiSpendingKeyDataSource.getZashiSpendingKey())
        val result = finalizeMigrationSchedule(sched, args.mode, args.backgroundAvailable)
        if (result != null) failure.value = result
    }

    private fun onBack() = proposeLce.guardLoading { navigationRouter.back() }

    private fun scheduledLabel(t: TransferProposal, mode: MigrationMode): StringResource {
        if (mode == MigrationMode.IMMEDIATE) return stringRes("Send immediately")
        val secondsUntil = estimatedSecondsBetweenHeights(t.anchorHeight, t.nextExecutableAfterHeight)
        return when {
            secondsUntil <= 0 -> stringRes("Ready now")
            secondsUntil < 3600 -> stringRes("~${(secondsUntil / 60).coerceAtLeast(1)} min")
            else -> stringRes("~${secondsUntil / 3600} hours")
        }
    }

    companion object {
        // Mock-only placeholder network fee (zatoshi) for the IMMEDIATE Review screen's Details
        // card. Mirrors OrchardMigrationSdkMock's NoteSplitProposal.fee precedent.
        private const val IMMEDIATE_MODE_MOCK_FEE_ZATOSHI = 1_000L
    }
}
