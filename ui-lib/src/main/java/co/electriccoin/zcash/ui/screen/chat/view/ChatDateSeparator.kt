// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
internal fun ChatDateSeparator(
    epochMillis: Long,
    modifier: Modifier = Modifier,
) {
    val today = stringResource(R.string.chat_room_date_today)
    val yesterday = stringResource(R.string.chat_room_date_yesterday)
    val label = formatDateSeparatorLabel(epochMillis, today, yesterday)
    ChatLabelSeparator(label = label, modifier = modifier)
}

@Composable
internal fun ChatUnreadSeparator(modifier: Modifier = Modifier) {
    ChatLabelSeparator(
        label = stringResource(R.string.chat_room_unread_messages),
        modifier = modifier,
    )
}

@Composable
private fun ChatLabelSeparator(
    label: String,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(c.border),
        )
        BasicText(
            text = label.uppercase(),
            style = ZappTheme.typography.eyebrow.copy(color = c.textSubtle),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(c.border),
        )
    }
}

private fun formatDateSeparatorLabel(
    epochMillis: Long,
    today: String,
    yesterday: String,
): String {
    val msgCal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val todayCal = Calendar.getInstance()
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val sevenDaysAgoMillis = todayCal.timeInMillis - SEVEN_DAYS_MILLIS

    return when {
        isSameDay(msgCal, todayCal) -> {
            today
        }

        isSameDay(msgCal, yesterdayCal) -> {
            yesterday
        }

        epochMillis >= sevenDaysAgoMillis -> {
            SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(epochMillis))
        }

        else -> {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMillis))
        }
    }
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000
