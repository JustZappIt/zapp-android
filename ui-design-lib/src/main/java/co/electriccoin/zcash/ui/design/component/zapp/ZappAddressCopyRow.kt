package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.getValue
import kotlinx.coroutines.delay

/** The wallet's own address, shortened, beside a copy square that confirms with a check. */
@Composable
fun ZappAddressCopyRow(
    address: StyledStringResource,
    copyContentDescription: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val c = ZappTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            BasicText(
                text = label.uppercase(),
                style =
                    ZappTheme.typography.eyebrow.copy(
                        color = c.textSubtle,
                        fontSize = 10.sp,
                        letterSpacing = 1.8.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = address.getValue(),
                style =
                    ZappTheme.typography.mono.copy(
                        color = c.textMuted,
                        fontSize = 12.sp,
                    ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            CopyIconButton(contentDescription = copyContentDescription, onClick = onCopy)
        }
    }
}

@Composable
private fun CopyIconButton(
    contentDescription: String,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    val haptic = LocalHapticFeedback.current
    // Inline ✓ confirmation: the system clipboard chip is not shown on non-admin
    // profiles (see CopyToClipboardUseCase), so the button itself must confirm.
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_CONFIRM_MS)
            copied = false
        }
    }
    val borderColor by
        animateColorAsState(
            targetValue = if (copied) c.success else c.border,
            animationSpec = tween(ZappMotion.STATE_MS, easing = ZappMotion.easing),
            label = "copyBorder",
        )
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .border(BorderStroke(1.dp, borderColor), RectangleShape)
                .clickable(onClick = {
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.ContextClick) }
                    copied = true
                    onClick()
                })
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = copied,
            animationSpec = tween(ZappMotion.STATE_MS, easing = ZappMotion.easing),
            label = "copyIcon",
        ) { showCheck ->
            if (showCheck) {
                BasicText(
                    text = "✓",
                    style =
                        ZappTheme.typography.chip.copy(
                            color = c.success,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                        ),
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_copy_shielded),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    colorFilter = ColorFilter.tint(c.accentText),
                )
            }
        }
    }
}

private const val COPY_CONFIRM_MS = 1_500L
