// Zapp fork of zodl-inc/zodl-android
// Stripped: Firebase Crashlytics removed; using local-only crash reporting
// Last synced from Zodl: 2409c4d7
package co.electriccoin.zcash.crash.android.internal

import android.content.Context
import co.electriccoin.zcash.crash.android.internal.local.LocalCrashReporter

class ListCrashReportersImpl : ListCrashReporters {
    override fun provideReporters(context: Context): List<CrashReporter> =
        listOfNotNull(
            LocalCrashReporter.getInstance(context),
        )
}
