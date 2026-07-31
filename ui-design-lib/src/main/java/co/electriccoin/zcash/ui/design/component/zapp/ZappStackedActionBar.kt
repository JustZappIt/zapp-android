package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Bottom action bar that stacks one or more full-width [ZappButton]s vertically.
 *
 * Provides the surface background, a 1dp top rule, and respects the system
 * navigation-bar insets. Pass as the `bottomBar` slot of a
 * [androidx.compose.material3.Scaffold] or `BlankBgScaffold`. For the
 * back-button + primary-action layout used on sub-screens, use
 * [ZappBottomActionBar] instead.
 */
@Composable
fun ZappStackedActionBar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(c.bg)
                .border(BorderStroke(1.dp, c.text), RectangleShape)
                .windowInsetsPadding(WindowInsets.navigationBars),
        content = content,
    )
}
