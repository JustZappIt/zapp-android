// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.support

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import co.electriccoin.zcash.ui.screen.chat.media.MediaPickEffect
import co.electriccoin.zcash.ui.screen.chat.media.rememberMediaPickHandlers

@Composable
internal fun SupportChatEffectsHandler(viewModel: SupportChatVM) {
    val handlers =
        rememberMediaPickHandlers(
            onMediaPicked = viewModel::onMediaPicked,
            onFilePicked = viewModel::onFilePicked,
            onCameraCaptured = viewModel::onCameraCaptured,
        )

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                MediaPickEffect.PickMedia -> handlers.pickMedia()
                MediaPickEffect.PickFile -> handlers.pickFile()
                MediaPickEffect.TakePhoto -> handlers.takePhoto()
            }
        }
    }
}
