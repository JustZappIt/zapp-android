package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Rectangular surface + 1dp border + content padding. Used for offramp progress cards, the P2P
 * balance card, and per-order rows in the P2P transactions screen — all three previously inlined
 * the same Column { background + border + padding } block.
 *
 * Pass [borderColor] to highlight failure states (e.g. `ZappTheme.colors.danger`); leave it
 * default for the neutral surface border.
 */
@Composable
fun ZappBorderedCard(
    modifier: Modifier = Modifier,
    borderColor: Color = ZappTheme.colors.border,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    padding: Dp = DEFAULT_PADDING,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(c.surface)
                .border(BorderStroke(1.dp, borderColor))
                .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

private val DEFAULT_PADDING = 14.dp
