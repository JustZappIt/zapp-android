package co.electriccoin.zcash.ui.common.provider

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import co.electriccoin.zcash.ui.BuildConfig

/**
 * Live, always-recomputed signal for whether this app can currently execute background work
 * reliably enough for [co.electriccoin.zcash.work.MigrationWorker]'s WorkManager job to actually
 * run at its scheduled time. Never cached — must be re-queried every time it's needed, since the
 * user can flip either underlying OS setting at any point from system Settings, completely outside
 * the app's control.
 *
 * Two independent signals feed this:
 * - Battery-optimization exemption ([PowerManager.isIgnoringBatteryOptimizations]) — the same
 *   check [co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryScreen] already made
 *   inline; extracted here so non-Compose code (a [android.content.BroadcastReceiver], a use case)
 *   can query it too, without duplicating the [PowerManager] call.
 * - OEM/system-level background restriction ([ActivityManager.isBackgroundRestricted], API 28+) —
 *   a stronger restriction some OEM skins (or the user, via App Info > Battery > "Restricted")
 *   can apply independently of the battery-optimization exemption; an app can be Doze-exempt yet
 *   still have this set.
 *
 * Deliberately NOT derived from whether the user tapped "Skip" on the onboarding
 * [co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryScreen] — that recorded a
 * one-time historical choice, not the current runtime state. The user may have granted the
 * exemption later from system Settings, or the OS/OEM may have applied or lifted a restriction
 * since — this must always reflect what's true right now.
 *
 * Kept single-arg so this stays trivially constructible by Koin's [org.koin.core.module.dsl.factoryOf]
 * — the SDK-version gate is factored out into the internal, directly-testable [isBackgroundExecutionAvailable]
 * top-level function instead of a second constructor parameter (a raw `Int` default of
 * `Build.VERSION.SDK_INT` would otherwise have to be resolvable from the Koin graph too).
 */
class IsBackgroundExecutionAvailableProvider(
    private val context: Context
) {
    fun isAvailable(): Boolean {
        if (BuildConfig.DEBUG && DebugForceBackgroundExecutionUnavailable.isForced(context)) return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return isBackgroundExecutionAvailable(
            isIgnoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true,
            isBackgroundRestricted = activityManager?.isBackgroundRestricted == true,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }
}

/**
 * Pure decision function extracted from [IsBackgroundExecutionAvailableProvider.isAvailable] so it's
 * directly unit-testable without needing Robolectric to fake `Build.VERSION.SDK_INT` (a raw static
 * field mockk can't intercept). [isBackgroundRestricted] is ignored below API 28, where
 * [ActivityManager.isBackgroundRestricted] doesn't exist.
 */
internal fun isBackgroundExecutionAvailable(
    isIgnoringBatteryOptimizations: Boolean,
    isBackgroundRestricted: Boolean,
    sdkInt: Int,
): Boolean {
    val effectivelyBackgroundRestricted = sdkInt >= Build.VERSION_CODES.P && isBackgroundRestricted
    return isIgnoringBatteryOptimizations && !effectivelyBackgroundRestricted
}
