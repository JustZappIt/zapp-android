package co.electriccoin.zcash.ui.screen.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.splash.ZappSplashConstants.MARK_SCALE
import co.electriccoin.zcash.ui.screen.splash.ZappSplashConstants.OPTICAL_CENTERING_X

/**
 * The full, centred Zapp Z (both halves at rest) as a static full-bleed backdrop. Same artwork
 * and theme colours as [ZappSplashAnimation]; used as the brand privacy screen behind app-access
 * authentication.
 */
@Composable
internal fun ZappZBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(colorResource(R.color.zapp_splash_bg))) {
        Image(
            painter = painterResource(R.drawable.zapp_z_top),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().scale(MARK_SCALE).offset(x = OPTICAL_CENTERING_X),
        )
        Image(
            painter = painterResource(R.drawable.zapp_z_bottom),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().scale(MARK_SCALE).offset(x = OPTICAL_CENTERING_X),
        )
    }
}
