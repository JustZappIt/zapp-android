package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@Composable
fun ZappSendActionBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isBackEnabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(ZappTheme.colors.surface)
                .border(BorderStroke(1.dp, ZappTheme.colors.border), RectangleShape)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = ZappTheme.spacing.xl + ZappTheme.spacing.xxs, vertical = ZappTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ZappBackButton(onClick = onBack, enabled = isBackEnabled)
        content()
    }
}
