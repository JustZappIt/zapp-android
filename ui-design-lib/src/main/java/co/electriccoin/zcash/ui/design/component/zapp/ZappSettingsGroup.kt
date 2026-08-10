package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Section label, a bordered column of [ZappRow]s, and an optional explanatory footer — the shape
 * every settings-style list on the Zapp tabs and detail screens uses.
 */
@Composable
fun ZappSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ZappTheme.colors
    Column(modifier = modifier) {
        ZappGroupHeader(text = title)
        ZappBorderedCard(
            modifier = Modifier.padding(horizontal = GUTTER),
            padding = 0.dp,
            content = content,
        )
        if (footer != null) {
            BasicText(
                text = footer,
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
                modifier = Modifier.padding(start = TEXT_GUTTER, end = TEXT_GUTTER, top = 8.dp),
            )
        }
    }
}

private val GUTTER = 14.dp
private val TEXT_GUTTER = 18.dp
