package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * A labelled wallet address, shortened for reading and copied whole by the trailing button.
 *
 * Wider than the default ellipsis: this stands in for a funds destination, and a first-8/last-6
 * match is exactly what address-poisoning tooling is built to produce.
 */
@Composable
fun ZappCopyableAddress(
    label: String,
    address: String,
    copyContentDescription: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    isCopied: Boolean = false,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(c.surfaceAlt, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
    ) {
        BasicText(
            text = label,
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = address.ellipsizeAddress(head = ADDRESS_HEAD, tail = ADDRESS_TAIL),
                style = ZappTheme.typography.mono.copy(color = c.text),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            ZappCopyIconButton(
                isCopied = isCopied,
                contentDescription = copyContentDescription,
                onClick = onCopy,
            )
        }
    }
}

private const val ADDRESS_HEAD = 14
private const val ADDRESS_TAIL = 10
