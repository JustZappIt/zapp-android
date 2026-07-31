package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Swiss-style top app bar: a small all-caps accent "eyebrow" label on the left and
 * a 40dp bordered close button on the right. Pass as the `topBar` slot of a
 * [androidx.compose.material3.Scaffold] or `BlankBgScaffold`.
 */
@Composable
fun ZappEyebrowTopAppBar(
    eyebrow: String,
    onClose: () -> Unit,
    closeContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 28.dp, end = 14.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = eyebrow.uppercase(),
            style =
                ZappTheme.typography.eyebrow.copy(
                    color = c.accent,
                    fontSize = 10.sp,
                    letterSpacing = 2.5.sp,
                    fontWeight = FontWeight.Black,
                ),
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = c.text),
                        onClick = onClose,
                    ).semantics {
                        contentDescription = closeContentDescription
                        role = Role.Button
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = c.text,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
