// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.common

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a chat-list relative-time label: "now" / "5m" / "3:42 PM" / "Tue" / "May 18".
 */
internal fun formatRelativeTime(epochMillis: Long): StringResource {
    val diff = System.currentTimeMillis() - epochMillis
    return when {
        diff < ONE_MINUTE_MS -> {
            stringRes(R.string.chat_list_time_now)
        }

        diff < ONE_HOUR_MS -> {
            stringRes(R.string.chat_list_time_minutes_short, (diff / ONE_MINUTE_MS).toInt())
        }

        diff < ONE_DAY_MS -> {
            stringRes(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis)))
        }

        diff < ONE_WEEK_MS -> {
            stringRes(SimpleDateFormat("EEE", Locale.getDefault()).format(Date(epochMillis)))
        }

        else -> {
            stringRes(SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis)))
        }
    }
}

private const val ONE_MINUTE_MS = 60_000L
private const val ONE_HOUR_MS = 3_600_000L
private const val ONE_DAY_MS = 86_400_000L
private const val ONE_WEEK_MS = 604_800_000L
