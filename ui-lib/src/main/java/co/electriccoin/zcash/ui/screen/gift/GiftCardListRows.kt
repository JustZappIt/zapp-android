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

/** [GiftCheckProgress.fraction] is 0..1; the string shows whole percent. */
private const val PERCENT = 100

@Composable
internal fun GiftCardRow(item: GiftCardListItem) {
    val spacing = ZappTheme.spacing
    val sharePickerText = stringResource(R.string.gift_card_list_share_picker)

    ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        GiftCardHeading(item)
        GiftCardDetails(item)
        // Absent rather than disabled once a card is collected. A greyed-out Share on a settled card
        // is an offer to do something there is no version of: its link is spent, and the row is a
        // receipt now. The check control hides on the same condition.
        item.handOff?.let { handOff ->
            ZappButton(
                text = stringResource(R.string.gift_card_list_share),
                variant = ZappButtonVariant.Secondary,
                onClick = { handOff.onShare(sharePickerText) },
                modifier = Modifier.fillMaxWidth(),
            )
            ZappButton(
                text = stringResource(R.string.gift_card_list_copy),
                variant = ZappButtonVariant.Ghost,
                onClick = handOff.onCopy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Its own row rather than a button beside Share: the label is a sentence, and squeezed
        // into a shared row it wrapped one character per line.
        GiftCardCheckAction(item.check)
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
    (item.check as? GiftCheckControl.Running)?.let {
        ZappSectionLabel(text = it.progress.progressText(), color = c.accentText)
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
private fun GiftCardCheckAction(check: GiftCheckControl) {
    if (check is GiftCheckControl.Hidden) return

    val isRunning = check is GiftCheckControl.Running
    ZappButton(
        text = stringResource(if (isRunning) R.string.gift_card_list_check_stop else R.string.gift_card_list_check),
        variant = ZappButtonVariant.Ghost,
        enabled = check !is GiftCheckControl.Blocked,
        onClick = {
            when (check) {
                is GiftCheckControl.Ready -> check.onCheck()
                is GiftCheckControl.Running -> check.onStop()
                is GiftCheckControl.Blocked, GiftCheckControl.Hidden -> Unit
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (check is GiftCheckControl.Blocked) {
        ZappSectionLabel(text = stringResource(check.reason.reasonRes()), color = ZappTheme.colors.textSubtle)
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
