package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Label left, value right: the one detail row for every money flow's summary and receipt. The value
 * is the emphasised half, and it ellipsizes rather than wrapping so a long one cannot push the
 * label off the row.
 */
@Composable
fun ZappSummaryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style =
                ZappTheme.typography.caption
                    .copy(color = ZappTheme.colors.textMuted, fontWeight = FontWeight.Medium),
        )
        BasicText(
            text = value,
            style =
                ZappTheme.typography.body
                    .copy(color = ZappTheme.colors.text, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = VALUE_GAP.dp),
        )
    }
}

private const val VALUE_GAP = 10
