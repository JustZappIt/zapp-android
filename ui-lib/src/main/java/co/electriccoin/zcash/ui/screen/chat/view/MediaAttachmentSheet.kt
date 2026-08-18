// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiModalBottomSheet
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import kotlinx.coroutines.launch

/**
 * Bottom sheet offering media-attachment options. Pass [onShareLocation] as null to omit the
 * location option (support chat doesn't need it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaAttachmentSheet(
    onChooseMedia: () -> Unit,
    onAttachFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onDismiss: () -> Unit,
    onShareLocation: (() -> Unit)? = null,
) {
    val c = ZappTheme.colors
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
        containerColor = c.surface,
        scrimColor = c.overlay,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
        ) {
            BasicText(
                text = stringResource(R.string.chat_media_sheet_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MediaOption(
                    icon = Icons.Default.Image,
                    label = stringResource(R.string.chat_media_option_media),
                    enabled = !isTransitioning,
                    onClick = { dismissThen(onChooseMedia) },
                    modifier = Modifier.weight(1f),
                )
                MediaOption(
                    icon = Icons.Default.AttachFile,
                    label = stringResource(R.string.chat_media_option_file),
                    enabled = !isTransitioning,
                    onClick = { dismissThen(onAttachFile) },
                    modifier = Modifier.weight(1f),
                )
                if (onShareLocation == null) {
                    MediaOption(
                        icon = Icons.Default.CameraAlt,
                        label = stringResource(R.string.chat_media_option_camera),
                        enabled = !isTransitioning,
                        onClick = { dismissThen(onTakePhoto) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (onShareLocation != null) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MediaOption(
                        icon = Icons.Default.CameraAlt,
                        label = stringResource(R.string.chat_media_option_camera),
                        enabled = !isTransitioning,
                        onClick = { dismissThen(onTakePhoto) },
                        modifier = Modifier.weight(1f),
                    )
                    MediaOption(
                        icon = Icons.Default.LocationOn,
                        label = stringResource(R.string.chat_media_option_location),
                        enabled = !isTransitioning,
                        onClick = { dismissThen(onShareLocation) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaOption(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(80.dp),
        shape = RectangleShape,
        color = c.surfaceAlt,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = c.accent,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            BasicText(
                text = label,
                style = ZappTheme.typography.chip.copy(color = c.text),
            )
        }
    }
}
