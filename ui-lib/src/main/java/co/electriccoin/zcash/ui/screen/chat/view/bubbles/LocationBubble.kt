// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view.bubbles

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.view.MessageStatusIndicator
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LocationBubble(message: ChatMessage, isFromMe: Boolean) {
    val context = LocalContext.current
    val parsed =
        try {
            JSONObject(message.content)
        } catch (_: Exception) {
            null
        }
    val lat = parsed?.optDouble("latitude") ?: 0.0
    val lng = parsed?.optDouble("longitude") ?: 0.0

    Surface(
        shape = RectangleShape,
        color = ZappTheme.colors.accent.copy(alpha = 0.1f),
        modifier = Modifier.widthIn(max = 280.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = ZappTheme.colors.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        if (isFromMe) {
                            stringResource(R.string.chat_bubble_location_shared)
                        } else {
                            stringResource(R.string.chat_bubble_location_received)
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = ZappTheme.colors.accent
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = String.format(Locale.US, "%.6f, %.6f", lat, lng),
                style = MaterialTheme.typography.bodyMedium,
                color = ZappTheme.colors.text
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier =
                    Modifier.clickable {
                        val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, geoUri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = ZappTheme.colors.accent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.chat_bubble_location_open_in_maps),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZappTheme.colors.accent
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZappTheme.colors.textMuted
                )
                if (isFromMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    MessageStatusIndicator(
                        status = message.status,
                        mutedColor = ZappTheme.colors.textMuted,
                        readColor = ZappTheme.colors.accent,
                    )
                }
            }
        }
    }
}
