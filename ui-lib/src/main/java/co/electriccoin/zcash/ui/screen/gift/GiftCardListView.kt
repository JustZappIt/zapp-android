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
            state.notice?.let { notice ->
                item { Banner(stringResource(notice.messageRes()), c.textMuted) }
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

private fun GiftCardListError.messageRes() =
    when (this) {
        GiftCardListError.LINK_FAILED -> R.string.gift_card_list_error_link
        GiftCardListError.SHARE_FAILED -> R.string.gift_card_list_error_share
        GiftCardListError.HANDOFF_FAILED -> R.string.gift_card_list_error_handoff
        GiftCardListError.CHECK_UNREACHABLE -> R.string.gift_card_list_error_unreachable
        GiftCardListError.CHECK_FAILED -> R.string.gift_card_list_error_check
    }

private fun GiftCardListNotice.messageRes() =
    when (this) {
        GiftCardListNotice.CHECK_FUNDING_PENDING -> R.string.gift_card_list_notice_funding_pending
    }

internal object GiftCardListTag {
    const val AMOUNT = "gift_card_list_amount"
}
