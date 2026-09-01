package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * A labelled wallet address, ellipsized to a head/tail and copied whole by the trailing button.
 * [copyContentDescription] should swap to a "copied" phrasing while [isCopied] holds.
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BasicText(
            text = label,
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = address.ellipsizeAddress(),
                style = ZappTheme.typography.mono.copy(color = c.text),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clickable(onClick = onCopy)
                        .semantics {
                            contentDescription = copyContentDescription
                            role = Role.Button
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = if (isCopied) c.success else c.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
