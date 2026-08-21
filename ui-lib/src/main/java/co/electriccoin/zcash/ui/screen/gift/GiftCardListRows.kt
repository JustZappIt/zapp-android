// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue

/** [GiftCardListItem.checkProgress] is 0..1; the string shows whole percent. */
private const val PERCENT = 100

@Composable
internal fun GiftCardRow(item: GiftCardListItem) {
    val spacing = ZappTheme.spacing
    val sharePickerText = stringResource(R.string.gift_card_list_share_picker)

    ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        GiftCardHeading(item)
        GiftCardDetails(item)
        ZappButton(
            text = stringResource(R.string.gift_card_list_share),
            variant = ZappButtonVariant.Secondary,
            enabled = item.onShare != null,
            onClick = { item.onShare?.invoke(sharePickerText) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Its own row rather than a button beside Share: the label is a sentence, and squeezed
        // into a shared row it wrapped one character per line.
        if (item.isCheckable) GiftCardCheckAction(item)
    }
}

@Composable
private fun GiftCardHeading(item: GiftCardListItem) {
    val c = ZappTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs)) {
        BasicText(
            text = item.amount.getValue(),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            modifier = Modifier.testTag(GiftCardListTag.AMOUNT),
        )
        ZappSectionLabel(
            text = stringResource(item.status.labelRes()),
            color = if (item.status.isAwaitingHandOff()) c.accentText else c.textMuted,
        )
    }
}

@Composable
private fun GiftCardDetails(item: GiftCardListItem) {
    val c = ZappTheme.colors
    val caption = ZappTheme.typography.caption.copy(color = c.textSubtle)

    item.createdAt?.let { BasicText(text = it.getValue(), style = caption) }
    item.expiry?.let { expiry ->
        val label = if (expiry.isPast) R.string.gift_card_list_expired else R.string.gift_card_list_expires
        BasicText(text = stringResource(label, expiry.date.getValue()), style = caption)
    }
    item.message?.let {
        BasicText(text = it, style = ZappTheme.typography.body.copy(color = c.textMuted))
    }
    item.lastCheckedAt?.let {
        BasicText(text = stringResource(R.string.gift_card_list_checked_unclaimed, it.getValue()), style = caption)
    }
    if (item.isChecking) {
        ZappSectionLabel(text = item.checkProgress.progressText(), color = c.accentText)
    }
}

/** Null progress is the connect phase: nothing is reported until the card's wallet reaches a server. */
@Composable
private fun GiftCheckProgress?.progressText(): String =
    when {
        this == null -> stringResource(R.string.gift_card_list_check_connecting)
        fraction == null -> stringResource(R.string.gift_card_list_check_scanning)
        else -> stringResource(R.string.gift_card_list_check_progress, (fraction * PERCENT).toInt())
    }

@Composable
private fun GiftCardCheckAction(item: GiftCardListItem) {
    val label = if (item.isChecking) R.string.gift_card_list_check_stop else R.string.gift_card_list_check
    ZappButton(
        text = stringResource(label),
        variant = ZappButtonVariant.Ghost,
        enabled = item.onCheck != null,
        onClick = { item.onCheck?.invoke() },
        modifier = Modifier.fillMaxWidth(),
    )
    item.checkBlockedReason?.let {
        ZappSectionLabel(text = stringResource(it.reasonRes()), color = ZappTheme.colors.textSubtle)
    }
}

@Composable
internal fun ReceivedGiftRow(item: ReceivedGiftItem) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing

    ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        BasicText(
            text = item.amount.getValue(),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text),
        )
        item.claimedAt?.let {
            BasicText(
                text = stringResource(R.string.gift_card_list_received_on, it.getValue()),
                style = ZappTheme.typography.caption.copy(color = c.textSubtle),
            )
        }
        item.message?.let {
            BasicText(text = it, style = ZappTheme.typography.body.copy(color = c.textMuted))
        }
    }
}

private fun GiftCardListStatus.isAwaitingHandOff() =
    this == GiftCardListStatus.SUBMITTED ||
        this == GiftCardListStatus.FUNDED ||
        this == GiftCardListStatus.UNRESOLVED

private fun GiftCardListStatus.labelRes() =
    when (this) {
        GiftCardListStatus.UNFUNDED -> R.string.gift_card_list_status_unfunded
        GiftCardListStatus.UNRESOLVED -> R.string.gift_card_list_status_unresolved
        GiftCardListStatus.SUBMITTED -> R.string.gift_card_list_status_submitted
        GiftCardListStatus.FUNDED -> R.string.gift_card_list_status_funded
        GiftCardListStatus.SHARED -> R.string.gift_card_list_status_shared
        GiftCardListStatus.CLAIMED -> R.string.gift_card_list_status_claimed
    }

private fun GiftCheckBlocked.reasonRes() =
    when (this) {
        GiftCheckBlocked.NO_TRANSACTION -> R.string.gift_card_list_check_blocked_no_tx
        GiftCheckBlocked.ANOTHER_RUNNING -> R.string.gift_card_list_check_blocked_busy
    }
