// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenProgressIndicator
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.stringRes

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

    // Which card is at the front of the deck, and whether it is turned over. Both are the sender's
    // place in the stack rather than anything the store knows, so they live here.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var isFlipped by rememberSaveable { mutableStateOf(false) }
    // A card can be collected or deleted out from under the selection; the deck falls back to the
    // one the sort already put first, which is whichever still needs handing out.
    val expandedId = state.items.firstOrNull { it.id == selectedId }?.id ?: state.items.firstOrNull()?.id

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
            // Negative, so each card laps over the one behind it the way cards in a wallet do.
            // Without it the rounded corners leave notches of page colour at every junction.
            verticalArrangement = Arrangement.spacedBy(-DECK_OVERLAP),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(bottom = spacing.xl + DECK_OVERLAP),
                    verticalArrangement = Arrangement.spacedBy(spacing.lg),
                ) {
                    if (state.isCorrupted) {
                        Banner(stringResource(R.string.gift_card_list_corrupted), c.danger)
                    }
                    state.error?.let { Banner(stringResource(it.messageRes()), c.danger) }
                    state.notice?.let { Banner(stringResource(it.messageRes()), c.textMuted) }
                    if (state.items.isEmpty()) {
                        Banner(stringResource(R.string.gift_card_list_empty), c.textMuted)
                    } else {
                        Banner(stringResource(R.string.gift_card_list_warning), c.textMuted)
                    }
                }
            }
            items(state.items, key = { it.id }) { item ->
                val isExpanded = item.id == expandedId
                Column {
                    GiftDeckCard(
                        item = item,
                        isExpanded = isExpanded,
                        isFlipped = isExpanded && isFlipped,
                        onSelect = {
                            selectedId = item.id
                            // A card comes to the front face up. Nobody hands you a card backwards.
                            isFlipped = false
                        },
                        onFlip = {
                            // Claims the selection as well. Without it the list can re-sort under
                            // an implicitly-selected card and open a different one face-down.
                            selectedId = item.id
                            isFlipped = !isFlipped
                        },
                    )
                    // Clearance under the open card, so the next one laps onto empty space rather
                    // than over the controls printed along its bottom edge.
                    if (isExpanded) {
                        Spacer(Modifier.height(DECK_OVERLAP + spacing.xl))
                    }
                }
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

/**
 * How far each card rides up over the one behind it: exactly the corner radius, so the rounded top
 * corners land on solid card rather than leaving notches of page colour at every junction.
 */
private val DECK_OVERLAP = GIFT_CARD_CORNER

internal object GiftCardListTag {
    const val AMOUNT = "gift_card_list_amount"
}

private fun previewItem(
    id: String,
    zatoshi: Long,
    tier: GiftCardTier,
    status: GiftCardListStatus,
    fiat: String,
    message: String? = null,
) = GiftCardListItem(
    id = id,
    amount = stringRes(Zatoshi(zatoshi)),
    fiat = stringRes(fiat),
    tier = tier,
    createdAt = stringRes("18 Aug 2026"),
    message = message,
    status = status,
    expiry = GiftExpiryDisplay(date = stringRes("1 Sep 2026"), isPast = false),
    lastCheckedAt = null,
    check = GiftCheckControl.Ready {},
    handOff = GiftHandOff(onShare = {}, onCopy = {}).takeIf { status != GiftCardListStatus.CLAIMED },
)

@PreviewScreens
@Composable
private fun GiftCardDeckPreview() =
    ProvideZappTheme {
        GiftCardListView(
            state =
                GiftCardListState(
                    items =
                        listOf(
                            previewItem(
                                id = "a",
                                zatoshi = 1_000_000_000,
                                tier = GiftCardTier.AMBER,
                                status = GiftCardListStatus.FUNDED,
                                fiat = "$600.00",
                                message = "Happy birthday — your first private money.",
                            ),
                            previewItem("b", 25_000_000, GiftCardTier.OBSIDIAN, GiftCardListStatus.SHARED, "$15.00"),
                            previewItem("c", 4_000_000, GiftCardTier.BONE, GiftCardListStatus.SHARED, "$2.40"),
                            previewItem("d", 6_000_000, GiftCardTier.SPENT, GiftCardListStatus.CLAIMED, "$3.60"),
                        ),
                    isCorrupted = false,
                    error = null,
                    notice = null,
                    onBack = {},
                )
        )
    }

@PreviewScreens
@Composable
private fun GiftCardDeckEmptyPreview() =
    ProvideZappTheme {
        GiftCardListView(
            state =
                GiftCardListState(
                    items = emptyList(),
                    isCorrupted = false,
                    error = null,
                    notice = null,
                    onBack = {},
                )
        )
    }
