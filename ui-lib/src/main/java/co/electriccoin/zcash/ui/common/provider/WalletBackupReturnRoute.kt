package co.electriccoin.zcash.ui.common.provider

import kotlin.reflect.KClass

/**
 * Where the recovery-phrase backup returns once the phrase is saved, for a screen that sent the user to back up
 * in the middle of its own task (Invest setup). Null, the usual case, returns to the tabs. The screen that sets it
 * clears it when it goes away, so a later backup started elsewhere never lands on a screen that is gone.
 */
class WalletBackupReturnRoute {
    @Volatile
    var route: KClass<*>? = null
}
