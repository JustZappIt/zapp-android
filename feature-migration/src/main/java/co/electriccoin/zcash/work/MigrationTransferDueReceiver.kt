package co.electriccoin.zcash.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.IsBackgroundExecutionAvailableProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock

/**
 * Fires when a scheduled migration transfer becomes due (armed by [MigrationDueAlarmScheduler]
 * alongside every [MigrationScheduler] WorkManager job), independent of whether
 * [MigrationWorker]'s WorkManager job has actually run yet — spec §6.4 "Transfer Ready to Send".
 *
 * Deliberately does the absolute minimum work: a system-service check
 * ([IsBackgroundExecutionAvailableProvider]) and a local encrypted-prefs read
 * ([MigrationPlanRepository]) — no network calls and no wallet/Synchronizer access — so it can run
 * even under the same Doze conditions that made background execution unavailable in the first
 * place.
 *
 * If background execution IS available, this is a no-op: [MigrationWorker] is expected to handle
 * the transfer itself, and this alarm exists purely as a fallback notifier for the case where it
 * can't. This intentionally does NOT check [cash.z.ecc.android.sdk.OrchardMigrationSdk
 * .hasOverdueTransfers] — that requires wallet access this receiver must not take on, and since the
 * alarm is armed to fire right at the transfer's due instant, by construction it's "just become
 * due" whenever it does fire.
 */
class MigrationTransferDueReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val migrationPlanRepository: MigrationPlanRepository by inject()
    private val migrationNotifier: MigrationNotifier by inject()
    private val isBackgroundExecutionAvailableProvider: IsBackgroundExecutionAvailableProvider by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val accountKeyId = intent.getStringExtra(MigrationDueAlarmScheduler.EXTRA_ACCOUNT_KEY_ID)

        if (isBackgroundExecutionAvailableProvider.isAvailable()) {
            Twig.debug { "MIGRATION_DIAG MigrationTransferDueReceiver: background execution available — no-op." }
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Read the plan of the account this alarm was armed for (carried in the intent
                // extra), not the currently-selected one — so a "ready to send" notification shows
                // the right account's transfer counts even after the user switches accounts. Falls
                // back to the selected account only for a pre-upgrade alarm with no extra.
                val plan =
                    if (accountKeyId != null) {
                        migrationPlanRepository.load(accountKeyId)
                    } else {
                        migrationPlanRepository.load()
                    }
                val next = plan?.nextPending
                if (plan != null && next != null && next.scheduledAt <= Clock.System.now()) {
                    Twig.debug {
                        "MIGRATION_DIAG MigrationTransferDueReceiver: transfer ${next.index + 1} of " +
                            "${plan.totalCount} due — notifying (accountKeyId=$accountKeyId)."
                    }
                    if (accountKeyId == null) {
                        Twig.warn {
                            "MIGRATION_DIAG MigrationTransferDueReceiver: no accountKeyId extra on alarm intent — falling back to offset 0 (pre-upgrade alarm)"
                        }
                    }
                    migrationNotifier.notifyTransferReadyToSend(accountKeyId.orEmpty(), next.index + 1, plan.totalCount)
                } else {
                    Twig.debug { "MIGRATION_DIAG MigrationTransferDueReceiver: no due transfer — no-op." }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
