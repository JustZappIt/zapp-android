// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.media

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import java.io.File

class CameraCaptureState(
    private val context: Context,
    private val onPhotoCaptured: (Uri) -> Unit
) {
    var pendingUri: Uri? = null
        private set

    var launcher: ((Uri) -> Unit)? = null

    fun launch() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
        pendingUri = uri
        launcher?.invoke(uri)
    }

    fun onResult(success: Boolean) {
        if (success) pendingUri?.let { onPhotoCaptured(it) }
        pendingUri = null
    }
}

@Composable
fun rememberCameraCaptureState(
    context: Context,
    onPhotoCaptured: (Uri) -> Unit
): CameraCaptureState {
    val state = remember { CameraCaptureState(context, onPhotoCaptured) }

    val takePictureLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            state.onResult(success)
        }

    LaunchedEffect(Unit) {
        state.launcher = { uri -> takePictureLauncher.launch(uri) }
    }

    return state
}
