package co.electriccoin.zcash.ui.screen.about.util

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import co.electriccoin.zcash.ui.R

object WebBrowserUtil {
    internal fun startActivity(
        activity: Activity,
        url: String
    ) {
        val intent =
            CustomTabsIntent
                .Builder()
                .setUrlBarHidingEnabled(true)
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
        runCatching {
            intent.launchUrl(activity, Uri.parse(url))
        }
    }

    internal fun openBrandedUrl(
        activity: Activity,
        url: String
    ) {
        val toolbarColor = Color.parseColor("#FF9417") // Zapp orange
        val colorParams =
            CustomTabColorSchemeParams
                .Builder()
                .setToolbarColor(toolbarColor)
                .setNavigationBarColor(toolbarColor)
                .build()
        val packageName = CustomTabsClient.getPackageName(activity, null)
        val builder =
            CustomTabsIntent
                .Builder()
                .setDefaultColorSchemeParams(colorParams)
                .setUrlBarHidingEnabled(true)
                .setShowTitle(false)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        if (packageName != null) {
            builder.setStartAnimations(activity, R.anim.slide_up, R.anim.fade_out)
            builder.setExitAnimations(activity, R.anim.fade_in, R.anim.slide_down)
        }
        runCatching {
            builder.build().launchUrl(activity, Uri.parse(url))
        }
    }
}
