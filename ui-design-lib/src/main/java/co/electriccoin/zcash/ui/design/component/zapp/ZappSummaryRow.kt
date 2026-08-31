package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
    valueColor: Color = ZappTheme.colors.text,
    /**
     * Turns the label into a tappable explanation. Set only where the row's number is one the user
     * can act on — the tap has to lead somewhere, or the icon is a promise the row does not keep.
     */
    info: ZappRowInfoAction? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LABEL_ICON_GAP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = label,
                style =
                    ZappTheme.typography.caption
                        .copy(color = ZappTheme.colors.textMuted, fontWeight = FontWeight.Medium),
            )
            info?.let { action ->
                Box(
                    modifier =
                        Modifier
                            .size(INFO_TAP_TARGET.dp)
                            .clickable(onClick = action.onClick)
                            .semantics {
                                role = Role.Button
                                contentDescription = action.contentDescription
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = ZappTheme.colors.textMuted,
                        modifier = Modifier.size(INFO_ICON_SIZE.dp),
                    )
                }
            }
        }
        BasicText(
            text = value,
            style = ZappTheme.typography.body.copy(color = valueColor, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = VALUE_GAP.dp),
        )
    }
}

private const val VALUE_GAP = 10
private const val LABEL_ICON_GAP = 2

/** Small enough to sit on a caption line. */
private const val INFO_ICON_SIZE = 14

/** Android's own minimum, and the size the feature's other info button already uses. */
private const val INFO_TAP_TARGET = 48
