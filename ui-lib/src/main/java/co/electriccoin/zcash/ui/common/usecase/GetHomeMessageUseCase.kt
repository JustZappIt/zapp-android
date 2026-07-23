package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.model.WalletSnapshot
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_DUST_THRESHOLD_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.MigrationAttentionKind
import co.electriccoin.zcash.ui.common.model.migration.affectedTransferIndices
import co.electriccoin.zcash.ui.common.model.migration.toMigrationRangeText
import co.electriccoin.zcash.ui.common.model.migration.toUiKind
import co.electriccoin.zcash.ui.common.provider.CrashReportingStorageProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsBackgroundExecutionAvailableProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageData
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.RuntimeMessage
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GetHomeMessageUseCase(
    private val walletBackupMessageUseCase: WalletBackupMessageUseCase,
    private val crashReportingStorageProvider: CrashReportingStorageProvider,
    private val walletSnapshotDataSource: WalletSnapshotDataSource,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val accountDataSource: AccountDataSource,
    private val messageAvailabilityDataSource: MessageAvailabilityDataSource,
    private val cache: HomeMessageCacheRepository,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val migrationPlanRepository: MigrationPlanRepository,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getOrchardBalance: GetOrchardBalanceUseCase,
    private val hasSeenMigrationCompleteStorageProvider: HasSeenMigrationCompleteStorageProvider,
    private val isBackgroundExecutionAvailableProvider: IsBackgroundExecutionAvailableProvider,
) {
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun observe(): Flow<HomeMessageData?> =
        channelFlow {
            val messages =
                combine(
                    observeRuntimeMessage(),
                    walletBackupMessageUseCase.observe(),
                    observeIsTorMessageVisible(),
                    observeIsExchangeRateMessageVisible(),
                    crashReportingStorageProvider.observe().map { it == null },
                ) { runtimeMessage, backup, isTorAvailable, isCCAvailable, isCrashReportingEnabled ->
                    createMessage(
                        runtimeMessage = runtimeMessage,
                        backup = backup,
                        isTorVisible = isTorAvailable,
                        isCurrencyConversionEnabled = isCCAvailable,
                        isCrashReportingVisible = isCrashReportingEnabled,
                    )
                }

            launch {
                walletSnapshotDataSource
                    .observe()
                    .filterNotNull()
                    .map { it.status }
                    .flatMapLatest {
                        when (it) {
                            Synchronizer.Status.STOPPED,
                            Synchronizer.Status.INITIALIZING -> emptyFlow()

                            else -> messages
                        }
                    }.distinctUntilChanged()
                    .collect { send(it) }
            }

            awaitClose()
        }.debounce(1.seconds)
            .distinctUntilChanged()
            .map { message -> prioritizeMessage(message) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeMigrationMessage(): Flow<HomeMessageData.Migration?> =
        accountDataSource.selectedAccount.flatMapLatest { account ->
            if (account == null) return@flatMapLatest flowOf(null)
            combine(
                migrationPlanRepository.observe(),
                hasSeenMigrationCompleteStorageProvider.observe(),
                observeReadyToSendSignal(),
            ) { plan, hasSeenComplete, readyToSendSignal ->
                val sdk = getOrchardMigrationSdk()
                val sdkState = sdk?.getMigrationState()
                // Only computed when actually needed (RequiresAttention) — an extra
                // getMigrationTransferStates() read on every other state would be wasted work.
                val (attentionKind, attentionRangeText) =
                    (sdkState as? MigrationState.RequiresAttention)?.let { requiresAttention ->
                        attentionInfoFor(sdk, requiresAttention.reason, plan)
                    } ?: (null to null)
                migrationMessageFor(
                    sdkState = sdkState,
                    plan = plan,
                    hasSeenComplete = hasSeenComplete,
                    orchardBalanceZatoshi = getOrchardBalance().value,
                    dustThresholdZatoshi = sdk?.migrationDustThresholdZatoshi() ?: MIGRATION_DUST_THRESHOLD_ZATOSHI,
                    isBackgroundExecutionAvailable = readyToSendSignal.isBackgroundExecutionAvailable,
                    hasOverdueTransfers = readyToSendSignal.hasOverdueTransfers,
                    attentionKind = attentionKind,
                    attentionRangeText = attentionRangeText,
                )
            }
        }

    private data class ReadyToSendSignal(
        val isBackgroundExecutionAvailable: Boolean,
        val hasOverdueTransfers: Boolean,
    )

    // Neither signal here is itself observable, so this polls on the same cadence
    // MigrationProgressVM.reallyOverdueFlow() already uses for hasOverdueTransfers() — cheap local
    // checks (no network), just re-evaluated periodically since wall-clock "has this transfer's due
    // time arrived yet" can't otherwise be recomputed reactively.
    private fun observeReadyToSendSignal(): Flow<ReadyToSendSignal> =
        flow {
            while (true) {
                emit(
                    ReadyToSendSignal(
                        isBackgroundExecutionAvailable = isBackgroundExecutionAvailableProvider.isAvailable(),
                        hasOverdueTransfers = getOrchardMigrationSdk()?.hasOverdueTransfers() ?: false,
                    )
                )
                delay(READY_TO_SEND_RECHECK_INTERVAL)
            }
        }

    // Spec §6.2/§6.3 home-banner support — see MigrationAttentionKind's doc. Correlates the
    // reason's affected transfer(s) by stable id (never array index, same as MigrationProgressVM's
    // withLiveState) rather than assuming every not-yet-completed cached transfer is affected.
    private suspend fun attentionInfoFor(
        sdk: OrchardMigrationSdk?,
        reason: AttentionReason,
        plan: co.electriccoin.zcash.ui.common.model.migration.MigrationPlan?,
    ): Pair<MigrationAttentionKind, String?> {
        val kind = reason.toUiKind()
        if (plan == null) return kind to null
        val liveStates: MigrationTransferStates? = sdk?.getMigrationTransferStates()
        val rangeText = reason
            .affectedTransferIndices(plan, liveStates, Clock.System.now().epochSeconds)
            .toMigrationRangeText()
        return kind to rangeText
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeShieldFundsMessage() =
        accountDataSource.selectedAccount.flatMapLatest { account ->
            when {
                account == null -> {
                    flowOf(null)
                }

                account.isShieldingAvailable -> {
                    messageAvailabilityDataSource.canShowShieldMessage
                        .map { canShowShieldMessage ->
                            when {
                                !canShowShieldMessage -> null
                                else -> HomeMessageData.ShieldFunds(account.transparent.balance)
                            }
                        }
                }

                else -> {
                    flowOf(null)
                }
            }
        }

    private data class RuntimeMessageInputs(
        val shieldFunds: HomeMessageData.ShieldFunds?,
        val migration: HomeMessageData.Migration?,
        val account: WalletAccount?,
        val walletSnapshot: WalletSnapshot
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeRuntimeMessage(): Flow<RuntimeMessage?> {
        return channelFlow {
            var firstSyncingMessage: HomeMessageData.Syncing? = null
            combine(
                observeShieldFundsMessage(),
                observeMigrationMessage(),
                accountDataSource.selectedAccount,
                walletSnapshotDataSource.observe().filterNotNull()
            ) { sf, mig, acc, ws -> RuntimeMessageInputs(sf, mig, acc, ws) }
                .collect { inputs ->
                    val shieldFundsMessage = inputs.shieldFunds
                    val migrationMessage = inputs.migration
                    val account = inputs.account
                    val walletSnapshot = inputs.walletSnapshot

                    if (walletSnapshot.status in listOf(Synchronizer.Status.STOPPED, Synchronizer.Status.INITIALIZING)) {
                        return@collect
                    }

                    // Priority order: disconnected -> synchronizer error -> syncing -> migration
                    // -> shield funds. Connectivity/sync issues now lead the chain — a user can't
                    // act on the migration banner while the wallet is disconnected, erroring, or
                    // still syncing, so those states take priority over it.
                    val message = createDisconnectedMessage(walletSnapshot)
                            ?: createSynchronizerErrorMessage(walletSnapshot)
                            ?: createSyncingMessage(
                                walletSnapshot,
                                syncMessageShownBefore = firstSyncingMessage != null,
                                someBalance = (account?.spendableShieldedBalance?.value ?: 0) > 0
                            )
                            ?: migrationMessage
                            ?: shieldFundsMessage

                    if (message is HomeMessageData.Syncing && firstSyncingMessage == null) {
                        firstSyncingMessage = message
                    } else if (message !is HomeMessageData.Syncing) {
                        firstSyncingMessage = null
                    }

                    send(message)
                }
        }
    }

    private fun observeIsExchangeRateMessageVisible() =
        exchangeRateRepository.state
            .map { it == ExchangeRateState.OptIn }
            .distinctUntilChanged()

    private fun observeIsTorMessageVisible() =
        isTorEnabledStorageProvider.observe().map { it == null }.distinctUntilChanged()

    private fun createMessage(
        runtimeMessage: RuntimeMessage?,
        backup: WalletBackupData,
        isTorVisible: Boolean,
        isCurrencyConversionEnabled: Boolean,
        isCrashReportingVisible: Boolean,
    ) = when {
        runtimeMessage != null -> runtimeMessage
        backup is WalletBackupData.Available -> HomeMessageData.Backup
        isTorVisible -> HomeMessageData.EnableTor
        isCurrencyConversionEnabled -> HomeMessageData.EnableCurrencyConversion
        isCrashReportingVisible -> HomeMessageData.CrashReport
        else -> null
    }

    private fun prioritizeMessage(message: HomeMessageData?): HomeMessageData? {
        val isSameMessageUpdate = message?.priority == cache.lastMessage?.priority // same but updated
        val someMessageBeenShown = cache.lastShownMessage != null // has any message been shown while app in fg
        val hasNoMessageBeenShownLately = cache.lastMessage == null // has no message been shown
        val isHigherPriorityMessage = (message?.priority ?: 0) > (cache.lastShownMessage?.priority ?: 0)
        val result =
            when {
                message == null -> {
                    null
                }

                message is RuntimeMessage -> {
                    message
                }

                isSameMessageUpdate -> {
                    message
                }

                isHigherPriorityMessage -> {
                    if (hasNoMessageBeenShownLately) {
                        if (someMessageBeenShown) null else message
                    } else {
                        message
                    }
                }

                else -> {
                    null
                }
            }

        if (result != null) {
            messageAvailabilityDataSource.onMessageShown()
            cache.lastShownMessage = result
        }
        cache.lastMessage = result

        Twig.debug {
            when {
                message == null -> "Home message: no message to show"
                result == null -> "Home message: ${message::class.simpleName} was filtered out"
                else -> "Home message: ${result::class.simpleName} shown"
            }
        }

        return result
    }

    private fun createSynchronizerErrorMessage(walletSnapshot: WalletSnapshot): HomeMessageData.Error? {
        if (walletSnapshot.synchronizerError == null ||
            (
                walletSnapshot.synchronizerError is SynchronizerError.Processor &&
                    walletSnapshot.synchronizerError.cause is CancellationException
            )
        ) {
            return null
        }

        return HomeMessageData.Error(walletSnapshot.synchronizerError)
    }

    private fun createDisconnectedMessage(walletSnapshot: WalletSnapshot): HomeMessageData.Disconnected? =
        if (walletSnapshot.status == Synchronizer.Status.DISCONNECTED) {
            HomeMessageData.Disconnected
        } else {
            null
        }

    private fun createSyncingMessage(
        walletSnapshot: WalletSnapshot,
        syncMessageShownBefore: Boolean,
        someBalance: Boolean,
    ): RuntimeMessage? = syncingMessageFor(walletSnapshot, syncMessageShownBefore, someBalance)

    private companion object {
        val READY_TO_SEND_RECHECK_INTERVAL = 15.seconds
    }
}

/**
 * The migration home-banner decision, extracted as a pure function so it's directly testable
 * without mocking the whole reactive [GetHomeMessageUseCase.observeMigrationMessage] pipeline
 * (mirrors [syncingMessageFor] below).
 *
 * [plan] takes priority over [sdkState] for choosing between the Complete banner and a fresh
 * REQUIRED-equivalent: the SDK's own [MigrationState] stays [MigrationState.Complete] until the
 * *next* round is actually committed (see the engine's `commit_preparation`/`build_preparation_unsigned`
 * docs), so it alone cannot distinguish "round just finished, more residual balance needs another
 * round" from "campaign genuinely done" — both look identical to the SDK. The app-side [plan] can:
 * `MigrationCompleteVM.onDone()` clears it (without setting [hasSeenComplete]) exactly when more
 * rounds are needed, so `plan == null` here means "treat this as if nothing has run yet."
 *
 * [isBackgroundExecutionAvailable] and [hasOverdueTransfers] together drive spec §6.4 "Transfer
 * Ready to Send": a narrower, *earlier* window than the general overdue/missed-transfer state
 * (`MigrationProgressVM`'s `hasOverdue`) — the next pending transfer's scheduled time has arrived,
 * background execution can't run it, but the SDK doesn't (yet) count it as overdue. Both default to
 * "don't show this banner" (available/not-overdue) so existing callers/tests that don't pass them
 * keep behaving exactly as before.
 */
internal fun migrationMessageFor(
    sdkState: MigrationState?,
    plan: co.electriccoin.zcash.ui.common.model.migration.MigrationPlan?,
    hasSeenComplete: Boolean,
    orchardBalanceZatoshi: Long,
    dustThresholdZatoshi: Long = MIGRATION_DUST_THRESHOLD_ZATOSHI,
    isBackgroundExecutionAvailable: Boolean = true,
    hasOverdueTransfers: Boolean = false,
    now: Instant = Clock.System.now(),
    // Additive — see HomeMessageData.Migration's doc. Both null unless sdkState is
    // RequiresAttention; callers precompute these (GetHomeMessageUseCase.attentionInfoFor()) so this
    // function stays a pure, synchronous decision with no SDK access of its own.
    attentionKind: MigrationAttentionKind? = null,
    attentionRangeText: String? = null,
): HomeMessageData.Migration? {
    val next = plan?.nextPending
    return when {
        // Spec §6.2/§6.3 — takes priority over InProgress/Complete below: a plan needing
        // re-confirmation is more actionable than its last-known progress snapshot. Falls through
        // to the ordinary branches below when plan is null (a defensive case — RequiresAttention in
        // practice always implies a plan/schedule already existed).
        sdkState is MigrationState.RequiresAttention && plan != null ->
            HomeMessageData.Migration(
                plan,
                attentionKind = attentionKind ?: sdkState.reason.toUiKind(),
                attentionRangeText = attentionRangeText,
            )

        sdkState is MigrationState.InProgress &&
            next != null &&
            !isBackgroundExecutionAvailable &&
            !hasOverdueTransfers &&
            next.scheduledAt <= now ->
            HomeMessageData.Migration(plan, isReadyToSend = true)

        sdkState is MigrationState.InProgress -> HomeMessageData.Migration(plan)

        // Gated on the real dust threshold, not just the SDK's per-round Complete state — a
        // multi-round Keystone migration reports Complete as soon as the *current* round's
        // transfers are all mined, even with a large residual balance still needing another
        // round. Without this, finishing an earlier round would incorrectly show the one-time
        // completion banner.
        sdkState == MigrationState.Complete &&
            plan != null &&
            !hasSeenComplete &&
            orchardBalanceZatoshi <= dustThresholdZatoshi ->
            // Stays visible until the user actually engages with it — marked seen in
            // MigrationCompleteVM.onDone(), not just for having been displayed.
            HomeMessageData.Migration(plan, isComplete = true)

        // Real Orchard-only balance (not the combined Sapling+Orchard spendableShieldedBalance —
        // this must never fire for a wallet whose Orchard balance is 0, even with real Sapling
        // funds). Falls through here regardless of what sdkState says once plan is null — covers
        // both "never migrated" and "a round finished, more residual balance needs another round."
        // Same dust threshold as above, not a bare `> 0L` — a truly-dust balance never needs a
        // migration prompt of its own.
        orchardBalanceZatoshi > dustThresholdZatoshi && plan == null -> HomeMessageData.Migration(null)

        else -> null
    }
}

internal const val SYNCING_BANNER_HIDE_BELOW_BLOCKS = 3456L

@Suppress("MagicNumber")
internal fun syncingMessageFor(
    walletSnapshot: WalletSnapshot,
    syncMessageShownBefore: Boolean,
    someBalance: Boolean,
): RuntimeMessage? {
    if (walletSnapshot.status != Synchronizer.Status.SYNCING) return null

    val progress = walletSnapshot.progress.decimal * 100f
    return if (walletSnapshot.restoringState == WalletRestoringState.RESYNCING) {
        HomeMessageData.Resyncing(progress)
    } else if (walletSnapshot.restoringState == WalletRestoringState.RESTORING) {
        HomeMessageData.Restoring(walletSnapshot.isSpendable && someBalance, progress)
    } else {
        if (!syncMessageShownBefore) {
            if (walletSnapshot.blocksRemaining < SYNCING_BANNER_HIDE_BELOW_BLOCKS) {
                null
            } else {
                HomeMessageData.Syncing(progress = progress)
            }
        } else {
            HomeMessageData.Syncing(progress = progress)
        }
    }
}
