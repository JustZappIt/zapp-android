package co.electriccoin.zcash.ui.screen.walletbackup

import kotlin.reflect.KClass

/**
 * Where the recovery-phrase backup returns once the phrase is saved. [TABS] is the usual case; a feature that sends
 * the user to back up in the middle of its own task names itself, and maps that to its screen through
 * [WalletBackupReturnRoutes], so the backup flow never depends on the feature. It travels in the navigation
 * arguments, which survive the process being killed while the user writes the phrase down.
 */
enum class WalletBackupReturnTarget { TABS, INVEST_SETUP }

/** The screen to go back to for a [WalletBackupReturnTarget]; null means the tabs. */
fun interface WalletBackupReturnRoutes {
    fun routeFor(target: WalletBackupReturnTarget): KClass<*>?
}
