// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import co.electriccoin.zcash.ui.R

/**
 * Returns an on-demand POST_NOTIFICATIONS requester. Keep this behind an explicit action, such as
 * chat's background-delivery toggle, so opening a screen does not prompt. [deniedMessage] is toasted
 * when the user says no, only the first time on this screen when [toastsOnce].
 */
@Composable
internal fun rememberNotificationPermissionRequester(
    @StringRes deniedMessage: Int = R.string.chat_notifications_permission_required,
    toastsOnce: Boolean = false,
    onResult: (Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentOnResult = rememberUpdatedState(onResult)
    val permissionRequiredMessage = stringResource(deniedMessage)
    var hasToasted by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && !(toastsOnce && hasToasted)) {
                hasToasted = true
                Toast
                    .makeText(
                        context,
                        permissionRequiredMessage,
                        Toast.LENGTH_SHORT,
                    ).show()
            }
            currentOnResult.value(granted)
        }

    return remember(context, launcher) {
        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                currentOnResult.value(true)
            } else {
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    currentOnResult.value(true)
                } else {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
