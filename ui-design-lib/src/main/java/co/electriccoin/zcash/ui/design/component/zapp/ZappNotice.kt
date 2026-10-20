package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** A flat notice with an accent (or danger) rule on its left, and optional [actions] under the text. */
@Composable
fun ZappNotice(
    body: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    isDanger: Boolean = false,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(if (isDanger) c.dangerSoft else c.surface, RectangleShape)
                .border(BorderStroke(1.dp, if (isDanger) c.danger else c.border), RectangleShape),
    ) {
        Box(
            Modifier
                .width(RULE_WIDTH)
                .fillMaxHeight()
                .background(if (isDanger) c.danger else c.accent, RectangleShape),
        )
        Column(
            modifier = Modifier.weight(1f).padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            title?.let {
                BasicText(
                    text = it,
                    style = ZappTheme.typography.rowTitle.copy(color = if (isDanger) c.danger else c.text),
                )
            }
            BasicText(text = body, style = ZappTheme.typography.caption.copy(color = c.textMuted))
            actions?.let {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    content = it,
                )
            }
        }
    }
}

private val RULE_WIDTH = 3.dp
