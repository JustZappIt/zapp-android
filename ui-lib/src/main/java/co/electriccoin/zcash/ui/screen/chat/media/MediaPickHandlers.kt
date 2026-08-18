// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.media

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import co.electriccoin.zcash.ui.R

/**
 * Holder for the View-side launchers a chat VM cannot reach on its own. Returned by
 * [rememberMediaPickHandlers] — the screen wires its `effects` SharedFlow to these.
 */
internal class MediaPickHandlers(
    val pickMedia: () -> Unit,
    val pickFile: () -> Unit,
    val takePhoto: () -> Unit,
)

/**
 * Allocates the activity-result launchers + camera permission flow once per host composable.
 *
 * [onMediaPicked], [onFilePicked], [onCameraCaptured] are the VM-side entry points fired after
 * the user makes a choice (or `null` is silently ignored). Run permission checks here, not in
 * the VM — `Application.checkSelfPermission` can fire before the resource overlay is wired up.
 */
@Composable
internal fun rememberMediaPickHandlers(
    onMediaPicked: (Uri) -> Unit,
    onFilePicked: (Uri) -> Unit,
    onCameraCaptured: (Uri) -> Unit,
): MediaPickHandlers {
    val context = LocalContext.current

    val mediaPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(onMediaPicked)
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onFilePicked)
        }

    val cameraCaptureState = rememberCameraCaptureState(context) { uri -> onCameraCaptured(uri) }

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraCaptureState.launch()
            } else {
                Toast
                    .makeText(
                        context,
                        context.getString(R.string.chat_room_toast_camera_permission_required),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }

    return remember(mediaPickerLauncher, filePickerLauncher, cameraCaptureState, cameraPermissionLauncher) {
        MediaPickHandlers(
            pickMedia = {
                mediaPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            pickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            takePhoto = {
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    cameraCaptureState.launch()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
        )
    }
}
