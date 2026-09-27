package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import kotlin.math.ceil

/** Spins while [isRefreshing], then finishes its turn. */
@Composable
fun ZappRefreshButton(
    isRefreshing: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isRefreshing) {
        while (isRefreshing) {
            rotation.animateTo(rotation.value + FULL_TURN, tween(TURN_MS, easing = LinearEasing))
        }
        rotation.animateTo(
            ceil(rotation.value / FULL_TURN) * FULL_TURN,
            tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
        )
    }
    Box(
        modifier =
            modifier
                .size(TOUCH_TARGET)
                .clickable(enabled = !isRefreshing, onClick = onClick)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    if (isRefreshing) disabled()
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            tint = if (isRefreshing) ZappTheme.colors.textMuted else ZappTheme.colors.text,
            modifier = Modifier.size(ICON_SIZE).rotate(rotation.value),
        )
    }
}

private const val FULL_TURN = 360f
private const val TURN_MS = 900
private val TOUCH_TARGET = 48.dp
private val ICON_SIZE = 20.dp
