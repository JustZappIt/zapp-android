// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiModalBottomSheet
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(
    isGroup: Boolean,
    onShareAddress: () -> Unit,
    onSendZec: () -> Unit,
    onSplitBill: () -> Unit,
    onAttachMedia: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var isTransitioning by remember { mutableStateOf(false) }
    val dismissThen: (() -> Unit) -> Unit = { action ->
        if (!isTransitioning) {
            isTransitioning = true
            scope.launch {
                sheetState.hide()
                action()
            }
        }
    }

    ZashiModalBottomSheet(
        onDismissRequest = {
            if (!isTransitioning) {
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = ZappTheme.colors.surface,
        scrimColor = ZappTheme.colors.overlay,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
        ) {
            AttachmentRow(
                icon = Icons.Default.QrCode2,
                label = stringResource(R.string.chat_attachment_option_share_address),
                enabled = !isTransitioning,
                onClick = { dismissThen(onShareAddress) },
            )
            HorizontalDivider(
                color = ZappTheme.colors.border,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            AttachmentRow(
                icon = Icons.AutoMirrored.Filled.Send,
                label = stringResource(R.string.chat_attachment_option_send_zec),
                enabled = !isTransitioning,
                onClick = { dismissThen(onSendZec) },
            )
            HorizontalDivider(
                color = ZappTheme.colors.border,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            AttachmentRow(
                icon = Icons.Default.Payments,
                label =
                    stringResource(
                        if (isGroup) {
                            R.string.chat_attachment_option_split_bill
                        } else {
                            R.string.chat_attachment_option_request_payment
                        }
                    ),
                enabled = !isTransitioning,
                onClick = { dismissThen(onSplitBill) },
            )
            HorizontalDivider(
                color = ZappTheme.colors.border,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            AttachmentRow(
                icon = Icons.Default.AttachFile,
                label = stringResource(R.string.chat_attachment_option_attach_media),
                enabled = !isTransitioning,
                onClick = { dismissThen(onAttachMedia) },
            )
        }
    }
}

@Composable
private fun AttachmentRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RectangleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    this.role = Role.Button
                    contentDescription = label
                }.padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = ZappTheme.colors.accent,
            modifier = Modifier.size(24.dp)
        )
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = ZappTheme.colors.text
        )
    }
}
