package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** Selector pill: one row of mutually exclusive [ZappSegment]s, each an optional icon beside its label. */
@Composable
fun ZappSegmentedSelector(
    segments: List<ZappSegment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(SELECTOR_INSET.dp),
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            val isSelected = index == selectedIndex
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = MIN_TOUCH_TARGET.dp)
                        .background(if (isSelected) c.bg else Color.Transparent, RectangleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = c.accent),
                            onClick = { onSelect(index) },
                        ).semantics {
                            role = Role.Tab
                            selected = isSelected
                        },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ICON_GAP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    segment.icon?.let { icon ->
                        Image(
                            painter = painterResource(icon),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(ICON_SIZE.dp)
                                    .alpha(if (isSelected) 1f else UNSELECTED_ICON_ALPHA),
                        )
                    }
                    BasicText(
                        text = segment.label,
                        style =
                            ZappTheme.typography.caption.copy(
                                color = if (isSelected) c.text else c.textMuted,
                            ),
                    )
                }
            }
        }
    }
}

private const val SELECTOR_INSET = 3
private const val SEGMENT_GAP = 2
private const val MIN_TOUCH_TARGET = 48
private const val ICON_GAP = 8
private const val ICON_SIZE = 20
private const val UNSELECTED_ICON_ALPHA = 0.5f
