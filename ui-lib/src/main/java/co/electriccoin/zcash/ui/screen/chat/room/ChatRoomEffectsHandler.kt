// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.room

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.screen.chat.media.rememberMediaPickHandlers
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bridges [ChatRoomVM]'s `effects` flow to Android platform launchers. The media-picker /
 * file-picker / camera flow is shared with the support chat via [rememberMediaPickHandlers];
 * the chat room layers on a location-sharing flow that the support chat does not need.
 */
@Composable
internal fun ChatRoomEffectsHandler(viewModel: ChatRoomVM) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mediaHandlers =
        rememberMediaPickHandlers(
            onMediaPicked = viewModel::onMediaPicked,
            onFilePicked = viewModel::onFilePicked,
            onCameraCaptured = viewModel::onCameraCaptured,
        )

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.any { it }) {
                scope.launch { shareLocation(context, viewModel) }
            } else {
                Toast
                    .makeText(
                        context,
                        context.getString(R.string.chat_room_toast_location_permission_required),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ChatRoomEffect.PickMedia -> {
                    mediaHandlers.pickMedia()
                }

                ChatRoomEffect.PickFile -> {
                    mediaHandlers.pickFile()
                }

                ChatRoomEffect.TakePhoto -> {
                    mediaHandlers.takePhoto()
                }

                is ChatRoomEffect.ShowToast -> {
                    Toast
                        .makeText(context, effect.message.getString(context), Toast.LENGTH_SHORT)
                        .show()
                }

                ChatRoomEffect.ShareLocation -> {
                    val granted =
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        scope.launch { shareLocation(context, viewModel) }
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("TooGenericExceptionCaught", "MissingPermission")
private suspend fun shareLocation(
    context: Context,
    viewModel: ChatRoomVM,
) {
    try {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val location =
            suspendCancellableCoroutine<android.location.Location?> { cont ->
                client
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        if (location != null) {
            viewModel.onLocationObtained(location.latitude, location.longitude, location.accuracy)
        } else {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.chat_room_toast_location_unavailable),
                    Toast.LENGTH_SHORT,
                ).show()
        }
    } catch (e: Exception) {
        Toast
            .makeText(
                context,
                context.getString(R.string.chat_room_toast_location_error_fmt, e.message.orEmpty()),
                Toast.LENGTH_SHORT,
            ).show()
    }
}
