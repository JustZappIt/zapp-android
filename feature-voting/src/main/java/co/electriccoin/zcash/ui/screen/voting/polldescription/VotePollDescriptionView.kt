// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.polldescription

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.R as DesignR

/**
 * What a poll is about, as a sheet. Upstream floats a haze-blurred header over the scrolling body;
 * with no frost in the fork the header is simply pinned above the scroll area and separated by a
 * hairline, which is the same affordance without the blur.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VotePollDescriptionView(
    state: VotePollDescriptionState?,
    sheetState: SheetState = rememberScreenModalBottomSheetState(),
) {
    ZashiScreenModalBottomSheet(
        state = state,
        sheetState = sheetState,
        dragHandle = null,
    ) { descriptionState, contentPadding ->
        val c = ZappTheme.colors
        val spacing = ZappTheme.spacing

        SheetHeader(onClose = descriptionState.onBack)

        Column(
            modifier =
                Modifier
                    .weight(1f, false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.xl3)
                    .padding(top = spacing.lg, bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            BasicText(
                text = descriptionState.title.getValue(),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
                modifier = Modifier.fillMaxWidth()
            )
            BasicText(
                text = descriptionState.description.getValue(),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
                modifier = Modifier.fillMaxWidth()
            )
            if (descriptionState.discussionUrl != null) {
                DiscussionRow(onClick = descriptionState.onDiscussionClick)
            }
        }
    }
}

@Composable
private fun SheetHeader(onClose: () -> Unit) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.bg)
                .padding(horizontal = ZappTheme.spacing.md)
                .padding(vertical = ZappTheme.spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = stringResource(R.string.coinVote_common_pollDescription),
            style = ZappTheme.typography.rowTitle.copy(color = c.text)
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .size(TOUCH_TARGET)
                    .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(DesignR.drawable.ic_navigation_close),
                contentDescription = stringResource(R.string.coinVote_common_close),
                tint = c.textMuted,
                modifier = Modifier.size(CLOSE_ICON)
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().size(HAIRLINE).background(c.border))
}

@Composable
private fun DiscussionRow(onClick: () -> Unit) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xl),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = spacing.md)
                .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.size(ICON_TILE).background(c.surfaceAlt, RectangleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_vote_message_chat),
                contentDescription = null,
                tint = c.text,
                modifier = Modifier.size(ICON)
            )
        }
        BasicText(
            text = stringResource(R.string.coinVote_proposalList_viewForumDiscussions),
            style = ZappTheme.typography.rowTitle.copy(color = c.text),
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(DesignR.drawable.ic_chevron_right),
            contentDescription = null,
            tint = c.textMuted,
            modifier = Modifier.size(CHEVRON)
        )
    }
}

private val TOUCH_TARGET = 44.dp
private val CLOSE_ICON = 20.dp
private val ICON_TILE = 40.dp
private val ICON = 20.dp
private val CHEVRON = 20.dp
private val HAIRLINE = 1.dp

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreens
@Composable
private fun VotePollDescriptionPreview() =
    ProvideZappTheme {
        VotePollDescriptionView(
            state = VotePollDescriptionState.preview.copy(discussionUrl = "https://forum.zcashcommunity.com")
        )
    }
