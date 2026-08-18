// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappFab
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZappNavBar
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.list.ChatListItemState
import co.electriccoin.zcash.ui.screen.chat.list.ChatListState
import co.electriccoin.zcash.ui.screen.chat.list.ChatListSupportRowState

@Composable
internal fun ChatListView(
    state: ChatListState,
    showBackButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ZappScreenHeader(
                title = state.title.getValue(),
                right = { NetworkChip(state = state.networkChip) },
            )

            when {
                state.isLoading && state.items.isEmpty() -> {
                    LoadingState(modifier = Modifier.weight(1f))
                }

                else -> {
                    val navBarBottom =
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding =
                            PaddingValues(
                                top = 4.dp,
                                bottom = navBarBottom + ZappNavBar.CLEARANCE_DP.dp,
                            ),
                    ) {
                        item(key = "support_row") {
                            SupportContactRow(state = state.supportRow)
                            ZappRowDivider(inset = true)
                        }

                        items(items = state.items, key = { it.id }) { item ->
                            Column(modifier = Modifier.animateItem()) {
                                SwipeToLeaveRow(
                                    item = item,
                                    onLeave = item.onLeaveSwipe,
                                ) {
                                    ConversationItem(item = item)
                                }
                                ZappRowDivider(inset = true)
                            }
                        }
                    }
                }
            }
        }

        val floatingBottom =
            if (showBackButton) {
                ZappNavBar.PUSHED_FLOATING_MARGIN_DP.dp
            } else {
                ZappNavBar.FAB_BOTTOM_PADDING_DP.dp
            }
        ZappFab(
            icon = Icons.AutoMirrored.Filled.Chat,
            contentDescription = state.newConversationContentDescription.getValue(),
            onClick = state.onNewConversationClick,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        end = 20.dp,
                        bottom = floatingBottom,
                    ),
        )

        if (showBackButton) {
            ZappBackButton(
                onClick = state.onBack,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(
                            start = 20.dp,
                            bottom = floatingBottom,
                        ),
            )
        }

        state.leaveDialog?.let { LeaveConfirmationDialog(state = it) }
    }

    state.networkSheet?.let { sheetState ->
        NetworkDetailsSheet(
            connectionStatus = sheetState.connectionStatus,
            peerCount = sheetState.peerCount,
            dhtHealth = sheetState.dhtHealth,
            connectionDetails = sheetState.connectionDetails,
            onDismiss = sheetState.onDismiss,
        )
    }

    state.tosDialog?.let { ChatTermsDialog(onAccept = it.onAccept, onDecline = it.onDecline) }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = ZappTheme.colors.accent)
    }
}

@Composable
private fun SupportContactRow(state: ChatListSupportRowState) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = state.onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.img_zapp_logo),
            contentDescription = null,
            modifier = Modifier.size(44.dp),
        )

        Spacer(Modifier.size(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = stringRes(R.string.support_chat_title).getValue(),
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = state.subtitle.getValue(),
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                maxLines = 1,
            )
        }

        if (state.totalUnreadCount > 0) {
            Box(
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .background(c.accent, RectangleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                BasicText(
                    text = "${state.totalUnreadCount}",
                    style = ZappTheme.typography.chip.copy(color = c.onAccent),
                )
            }
        }
    }
}
