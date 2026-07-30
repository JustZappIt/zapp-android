package co.electriccoin.zcash.ui.common.migration

import android.content.Intent
import androidx.navigation.NavGraphBuilder
import co.electriccoin.zcash.ui.common.repository.MigrationHomeMessage
import co.electriccoin.zcash.ui.screen.home.HomeMessageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/*
 * Seam between ui-lib (the app "core") and the feature-migration module. ui-lib never imports
 * feature-migration classes — it talks exclusively to these contracts, and the app module wires
 * the implementations in via Koin (`featureMigrationModule`). When the migration era ends, delete
 * the feature module, its Koin module and these contracts' call-sites.
 */

/** Produces the migration home banner: the reactive payload and its UI state incl. click routing. */
interface MigrationHomeMessageSource {
    fun observe(): Flow<MigrationHomeMessage?>

    fun createMessageState(data: MigrationHomeMessage): HomeMessageState
}

/** True while a migration plan is active — the daily background [SyncWorker] yields to Lane A. */
interface MigrationGate {
    suspend fun isMigrationActive(): Boolean
}

/**
 * Foreground SYNCED hook (prove + reconcile + lane revival). Fired by SynchronizerProviderImpl on
 * every SYNCED transition; the implementation no-ops when no migration plan is active.
 */
interface MigrationSyncedHook {
    suspend fun onSynced()
}

/** App-shell entry points (MainActivity, RootNavGraph, DebugVM). */
interface MigrationAppHooks {
    /**
     * Handles migration intent extras (notification deep links, the debug E2E driver). Returns
     * true when the intent was recognized and handled.
     */
    fun handleIntent(intent: Intent, scope: CoroutineScope): Boolean

    /** App-open / foreground migration catch-up (recovery routing + lane revival). */
    suspend fun checkRecovery()
}

/** Installs the migration destinations into the wallet nav graph. */
interface MigrationNavContributor {
    fun contribute(navGraphBuilder: NavGraphBuilder)
}

/**
 * Send-pipeline nav seam: the IMMEDIATE-mode Keystone cancel path pops back to Migration Review
 * (Send was never on that back stack — see CancelProposalFlowUseCase).
 */
interface MigrationNavigator {
    fun backToMigrationReview()
}

/** Debug-menu migration actions; each returns the result text the debug screen displays. */
interface MigrationDebugActions {
    suspend fun restartMigration(): String

    suspend fun simulateTorFailure(): String
}
