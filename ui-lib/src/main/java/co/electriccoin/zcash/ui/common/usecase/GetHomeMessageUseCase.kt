package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.model.WalletSnapshot
import co.electriccoin.zcash.ui.common.provider.CrashReportingStorageProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageData
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import co.electriccoin.zcash.ui.common.repository.RuntimeMessage
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

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
            ) { plan, hasSeenComplete ->
                migrationMessageFor(
                    sdkState = getOrchardMigrationSdk()?.getMigrationState(),
                    plan = plan,
                    hasSeenComplete = hasSeenComplete,
                    orchardBalanceZatoshi = getOrchardBalance().value,
                )
            }
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

                    // migrationMessage leads the chain — the migration banner is the highest
                    // product priority while actionable (must stay visible over
                    // disconnected/error/syncing, not be hidden behind them).
                    val message =
                        migrationMessage
                            ?: createDisconnectedMessage(walletSnapshot)
                            ?: createSynchronizerErrorMessage(walletSnapshot)
                            ?: createSyncingMessage(
                                walletSnapshot,
                                syncMessageShownBefore = firstSyncingMessage != null,
                                someBalance = (account?.spendableShieldedBalance?.value ?: 0) > 0
                            )
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
 */
internal fun migrationMessageFor(
    sdkState: MigrationState?,
    plan: co.electriccoin.zcash.ui.common.model.migration.MigrationPlan?,
    hasSeenComplete: Boolean,
    orchardBalanceZatoshi: Long,
): HomeMessageData.Migration? =
    when {
        sdkState is MigrationState.InProgress -> HomeMessageData.Migration(plan)

        sdkState == MigrationState.Complete && plan != null && !hasSeenComplete ->
            // Stays visible until the user actually engages with it — marked seen in
            // MigrationCompleteVM.onDone(), not just for having been displayed.
            HomeMessageData.Migration(plan, isComplete = true)

        // Real Orchard-only balance (not the combined Sapling+Orchard spendableShieldedBalance —
        // this must never fire for a wallet whose Orchard balance is 0, even with real Sapling
        // funds). Falls through here regardless of what sdkState says once plan is null — covers
        // both "never migrated" and "a round finished, more residual balance needs another round."
        orchardBalanceZatoshi > 0L && plan == null -> HomeMessageData.Migration(null)

        else -> null
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
