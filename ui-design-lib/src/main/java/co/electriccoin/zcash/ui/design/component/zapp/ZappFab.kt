package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.animation.pressScale
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@Composable
fun ZappFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .size(FAB_SIZE_DP.dp)
                .pressScale(interactionSource)
                .shadow(elevation = 4.dp, shape = RectangleShape, clip = false)
                .background(c.accent, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = c.onAccent, bounded = true),
                    onClick = onClick,
                ).semantics(mergeDescendants = true) { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = c.onAccent,
            modifier = Modifier.size(24.dp),
        )
    }
}

private const val FAB_SIZE_DP = 56
