package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Bordered card holding a monospaced value (key, address) with an optional [leading] visual,
 * [trailing] action and explanatory [caption] underneath.
 */
@Composable
fun ZappValueCard(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    caption: String? = null,
    maxLines: Int = DEFAULT_MAX_LINES,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = ZappTheme.colors
    Column(modifier = modifier) {
        ZappBorderedCard(modifier = Modifier.padding(horizontal = GUTTER)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                leading?.invoke()
                Column(modifier = Modifier.weight(1f)) {
                    if (label != null) {
                        BasicText(
                            text = label,
                            style = ZappTheme.typography.caption.copy(color = c.textMuted),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    BasicText(
                        text = value,
                        style = ZappTheme.typography.mono.copy(color = c.text),
                        maxLines = maxLines,
                    )
                }
                trailing?.invoke()
            }
        }
        if (caption != null) {
            BasicText(
                text = caption,
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
                modifier = Modifier.padding(start = TEXT_GUTTER, end = TEXT_GUTTER, top = 8.dp),
            )
        }
    }
}

private const val DEFAULT_MAX_LINES = 3
private val GUTTER = 14.dp
private val TEXT_GUTTER = 18.dp
