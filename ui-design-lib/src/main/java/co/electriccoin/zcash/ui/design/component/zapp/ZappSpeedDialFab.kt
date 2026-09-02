package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

data class ZappSpeedDialAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/**
 * A [ZappFab] that expands into a vertical stack of labelled [actions]. Collapsed
 * it is a single accent FAB; tapping it rotates the `+` into a `×` and reveals the
 * actions above it over a tap-to-dismiss scrim. Selecting an action collapses first,
 * then fires its [ZappSpeedDialAction.onClick].
 *
 * Pass [fabPadding] for the bottom/end offset (nav-bar clearance) and a full-size
 * [modifier] so the scrim can cover the screen; empty regions stay click-through.
 */
@Composable
fun ZappSpeedDialFab(
    expandContentDescription: String,
    collapseContentDescription: String,
    actions: List<ZappSpeedDialAction>,
    fabPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) ROTATION_EXPANDED else 0f,
        label = "speedDialRotation",
    )

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(c.overlay)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { expanded = false },
                        ),
            )
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(fabPadding),
            horizontalAlignment = Alignment.End,
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(bottom = ACTION_GAP_DP.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp),
                ) {
                    actions.forEach { action ->
                        SpeedDialActionRow(
                            action = action,
                            onClick = {
                                expanded = false
                                action.onClick()
                            },
                        )
                    }
                }
            }

            ToggleFab(
                rotation = rotation,
                contentDescription =
                    if (expanded) collapseContentDescription else expandContentDescription,
                onClick = { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun SpeedDialActionRow(
    action: ZappSpeedDialAction,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .background(c.surface, RectangleShape)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            BasicText(
                text = action.label,
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
            )
        }
        Spacer(modifier = Modifier.width(ACTION_GAP_DP.dp))
        ZappFab(
            icon = action.icon,
            contentDescription = action.label,
            onClick = onClick,
        )
    }
}

@Composable
private fun ToggleFab(
    rotation: Float,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .size(FAB_SIZE_DP.dp)
                .shadow(elevation = 4.dp, shape = RectangleShape, clip = false)
                .background(c.accent, RectangleShape)
                .border(BorderStroke(1.dp, c.accentBorder), RectangleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = c.onAccent, bounded = true),
                    onClick = onClick,
                ).semantics(mergeDescendants = true) { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = contentDescription,
            tint = c.onAccent,
            modifier =
                Modifier
                    .size(24.dp)
                    .rotate(rotation),
        )
    }
}

private const val FAB_SIZE_DP = 56
private const val ACTION_GAP_DP = 12
private const val ROTATION_EXPANDED = 45f
