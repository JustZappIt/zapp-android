package co.electriccoin.zcash.ui.common.provider

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.getSystemService
import co.electriccoin.zcash.spackle.getPackageInfoCompat
import xyz.justzappit.offramp.onramp.OnrampDeviceSignals
import xyz.justzappit.offramp.onramp.OnrampDeviceSignalsProvider
import xyz.justzappit.offramp.onramp.OnrampScreeningSessionProvider
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the screening record merchants filter on. Field names and units mirror the browser APIs
 * the service was built against, so several values are translated rather than reported natively.
 */
internal class AndroidOnrampDeviceSignalsProvider(
    private val context: Context,
    private val screeningSession: OnrampScreeningSessionProvider,
) : OnrampDeviceSignalsProvider {
    override suspend fun collect(): OnrampDeviceSignals {
        val metrics = context.resources.displayMetrics
        val locale = Locale.getDefault()
        val timeZone = TimeZone.getDefault()
        return OnrampDeviceSignals(
            userAgent = System.getProperty("http.agent").orEmpty(),
            platform = PLATFORM,
            language = locale.toLanguageTag(),
            languages = listOf(locale.toLanguageTag()),
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            devicePixelRatio = metrics.density.toDouble(),
            timezone = timeZone.id,
            // JS convention: minutes WEST of UTC, so IST (UTC+5:30) reports -330.
            timezoneOffset = -(timeZone.rawOffset / MILLIS_PER_MINUTE),
            cookiesEnabled = true,
            doNotTrack = null,
            online = connectionType() != null,
            touchSupport = true,
            maxTouchPoints = MAX_TOUCH_POINTS,
            vendor = Build.MANUFACTURER,
            appVersion = appVersion(),
            colorDepth = COLOR_DEPTH,
            pixelDepth = COLOR_DEPTH,
            connectionType = connectionType(),
            deviceMemory = deviceMemoryGb(),
            hardwareConcurrency = Runtime.getRuntime().availableProcessors(),
            seonSession = screeningSession.session(),
        )
    }

    private fun appVersion(): String =
        context.packageManager
            .getPackageInfoCompat(context.packageName, 0L)
            .versionName
            .orEmpty()

    private fun connectionType(): String? {
        val capabilities =
            context
                .getSystemService<ConnectivityManager>()
                ?.let { it.getNetworkCapabilities(it.activeNetwork) }
        return when {
            capabilities == null -> null
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> CONNECTION_WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> CONNECTION_ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CONNECTION_CELLULAR
            else -> null
        }
    }

    private fun deviceMemoryGb(): Double? {
        val manager = context.getSystemService<ActivityManager>() ?: return null
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return info.totalMem.toDouble() / BYTES_PER_GB
    }

    private companion object {
        const val PLATFORM = "Android"
        const val CONNECTION_WIFI = "wifi"
        const val CONNECTION_ETHERNET = "ethernet"
        const val CONNECTION_CELLULAR = "4g"
        const val MILLIS_PER_MINUTE = 60_000
        const val MAX_TOUCH_POINTS = 5
        const val COLOR_DEPTH = 24
        const val BYTES_PER_GB = 1_073_741_824.0
    }
}
