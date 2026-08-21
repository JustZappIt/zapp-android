// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappCopyIconButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue

@Composable
internal fun GiftCardListView(
    state: GiftCardListState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing

    // Amounts and messages, and a clipboard hand-off. Same treatment as the ready screen.
    if (shouldSecureScreen) {
        SecureScreen()
    }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = {
            ZappScreenHeader(
                title = stringResource(R.string.gift_card_list_title),
                subtitle = stringResource(R.string.gift_card_list_subtitle),
                right = { if (state.hasArchived) ArchivedToggle(state) },
            )
        },
        bottomBar = { ZappBottomActionBar(onBack = state.onBack) },
    ) { contentPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            contentPadding = PaddingValues(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            if (state.isCorrupted) {
                item { Banner(stringResource(R.string.gift_card_list_corrupted), c.danger) }
            }
            state.error?.let { error ->
                item { Banner(stringResource(error.messageRes()), c.danger) }
            }
            if (state.items.isEmpty()) {
                item { Banner(stringResource(R.string.gift_card_list_empty), c.textMuted) }
            } else {
                item { Banner(stringResource(R.string.gift_card_list_warning), c.textMuted) }
            }
            items(state.items, key = { it.id }) { GiftCardRow(it) }
            if (state.received.isNotEmpty()) {
                item { ZappGroupHeader(text = stringResource(R.string.gift_card_list_received_title)) }
                items(state.received, key = { it.address }) { ReceivedGiftRow(it) }
            }
        }
    }
}

@Composable
private fun ArchivedToggle(state: GiftCardListState) {
    ZappSectionLabel(
        text =
            stringResource(
                if (state.isShowingArchived) {
                    R.string.gift_card_list_hide_archived
                } else {
                    R.string.gift_card_list_show_archived
                }
            ),
        color = ZappTheme.colors.accentText,
        modifier = Modifier.clickable(onClick = state.onToggleArchived),
    )
}

@Composable
private fun GiftCardRow(item: GiftCardListItem) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    val sharePickerText = stringResource(R.string.gift_card_list_share_picker)

    ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
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
            item.onCopy?.let { onCopy ->
                ZappCopyIconButton(
                    isCopied = item.isCopied,
                    contentDescription = stringResource(R.string.gift_card_list_copy),
                    onClick = onCopy,
                )
            }
        }

        item.createdAt?.let {
            BasicText(text = it.getValue(), style = ZappTheme.typography.caption.copy(color = c.textSubtle))
        }
        item.expiry?.let { expiry ->
            BasicText(
                text =
                    stringResource(
                        if (expiry.isPast) {
                            R.string.gift_card_list_expired
                        } else {
                            R.string.gift_card_list_expires
                        },
                        expiry.date.getValue(),
                    ),
                style = ZappTheme.typography.caption.copy(color = c.textSubtle),
            )
        }
        item.message?.let {
            BasicText(text = it, style = ZappTheme.typography.body.copy(color = c.textMuted))
        }
        if (item.isArchived) {
            ZappSectionLabel(text = stringResource(R.string.gift_card_list_archived_badge), color = c.textSubtle)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            ZappButton(
                text = stringResource(R.string.gift_card_list_share),
                variant = ZappButtonVariant.Secondary,
                enabled = item.onShare != null,
                onClick = { item.onShare?.invoke(sharePickerText) },
                modifier = Modifier.weight(1f),
            )
            item.onCheck?.let {
                ZappButton(
                    text =
                        stringResource(
                            if (item.isChecking) {
                                R.string.gift_card_list_checking
                            } else {
                                R.string.gift_card_list_check
                            }
                        ),
                    variant = ZappButtonVariant.Ghost,
                    enabled = !item.isChecking,
                    onClick = it,
                )
            }
            item.onArchive?.let {
                ZappButton(
                    text = stringResource(R.string.gift_card_list_archive),
                    variant = ZappButtonVariant.Ghost,
                    onClick = it,
                )
            }
        }
    }
}

@Composable
private fun ReceivedGiftRow(item: ReceivedGiftItem) {
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

@Composable
private fun Banner(text: String, color: Color) {
    BasicText(
        text = text,
        style = ZappTheme.typography.caption.copy(color = color),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun GiftCardListLoading(modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg),
        verticalArrangement = Arrangement.Center,
    ) {
        ZappScreenProgressIndicator()
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

private fun GiftCardListError.messageRes() =
    when (this) {
        GiftCardListError.LINK_FAILED -> R.string.gift_card_list_error_link
        GiftCardListError.SHARE_FAILED -> R.string.gift_card_list_error_share
        GiftCardListError.CHECK_FAILED -> R.string.gift_card_list_error_check
    }

internal object GiftCardListTag {
    const val AMOUNT = "gift_card_list_amount"
}
