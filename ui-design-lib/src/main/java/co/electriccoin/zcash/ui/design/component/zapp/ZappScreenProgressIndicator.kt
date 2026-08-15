package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Full-screen cold-load spinner in the Zapp accent — the counterpart to
 * [co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator], which hardcodes the
 * Zashi white.
 */
@Composable
fun ZappScreenProgressIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier.fillMaxSize().then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = ZappTheme.colors.accent)
    }
}
