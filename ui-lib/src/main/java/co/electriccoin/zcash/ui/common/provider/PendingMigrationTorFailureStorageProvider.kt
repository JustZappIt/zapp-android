package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey

/**
 * Persisted flag remembering that a background migration send attempt failed specifically because
 * of Tor connectivity and hasn't been resolved yet. Distinct from the in-memory
 * [co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepository], which
 * only carries a single interactive retry decision and is explicitly not meant to survive process
 * death — this flag is what connects a *background* Tor failure to app-open recovery routing
 * ([co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCase]) across process
 * restarts. Set to `true` when `MigrationWorker` hits a non-retryable network error while Tor was
 * in use, and cleared back to `false` on the one unambiguous "problem resolved" signal — a
 * subsequent successful transfer (`MigrationSendingVM.sendOnce()`'s `TransferResult.Success`
 * branch). Backed by regular (non-encrypted) app storage, wiped on uninstall.
 */
interface PendingMigrationTorFailureStorageProvider : BooleanStorageProvider

class PendingMigrationTorFailureStorageProviderImpl(
    override val preferenceHolder: StandardPreferenceProvider,
) : BaseBooleanStorageProvider(key = PreferenceKey("pending_migration_tor_failure")),
    PendingMigrationTorFailureStorageProvider
