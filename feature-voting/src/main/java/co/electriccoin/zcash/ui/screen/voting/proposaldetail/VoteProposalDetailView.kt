// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.proposaldetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.voting.component.VoteAppBar
import co.electriccoin.zcash.ui.screen.voting.component.VoteRadioIndicator
import co.electriccoin.zcash.ui.screen.voting.component.VoteViewMoreChip
import co.electriccoin.zcash.ui.screen.voting.proposaldetail.bottomsheet.PollEndedBottomSheet
import co.electriccoin.zcash.ui.R as AppR
import co.electriccoin.zcash.ui.design.R as DesignR

/**
 * One question, and this wallet's answer to it. A locked proposal (the poll closed, or the vote is
 * already cast) drops the whole action bar rather than showing a disabled button — there is nothing
 * to do here but read.
 */
@Composable
fun VoteProposalDetailView(state: VoteProposalDetailState) {
    ZashiConfirmationBottomSheet(state = state.unverifiedPollWarningSheet)
    ZashiConfirmationBottomSheet(state = state.unansweredSheet)

    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    var isDescriptionExpanded by remember { mutableStateOf(false) }
    var isDescriptionOverflowing by remember { mutableStateOf(false) }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = { VoteAppBar(title = state.positionLabel.getValue()) },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!state.isLocked && state.forumUrl != null) {
                    ForumLinkRow(onClick = state.onForumClick)
                }
                ZappBottomActionBar(
                    onBack = state.onBack,
                    primaryAction = if (state.isLocked) null else ({ NextButton(state) })
                )
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.xl)
        ) {
            BasicText(
                text = state.title.getValue(),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text)
            )

            val description = state.description.getValue()
            if (description.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                    BasicText(
                        text = description,
                        style = ZappTheme.typography.body.copy(color = c.textMuted),
                        maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else MAX_LINES,
                        overflow = if (isDescriptionExpanded) TextOverflow.Visible else TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (!isDescriptionExpanded) isDescriptionOverflowing = result.hasVisualOverflow
                        },
                    )
                    if (isDescriptionOverflowing || isDescriptionExpanded) {
                        VoteViewMoreChip(
                            isExpanded = isDescriptionExpanded,
                            onClick = { isDescriptionExpanded = !isDescriptionExpanded },
                        )
                    }
                }
            }

            VoteOptions(state.options)
        }
    }

    if (state.showPollEndedSheet) {
        PollEndedBottomSheet(
            onViewResults = state.onPollEndedViewResults,
            onClose = state.onPollEndedClose,
        )
    }
}

@Composable
private fun NextButton(state: VoteProposalDetailState) {
    val label =
        if (state.isEditingFromReview) AppR.string.coinVote_common_save else AppR.string.coinVote_common_next
    ZappButton(text = stringResource(label), onClick = state.onNext)
}

@Composable
private fun ForumLinkRow(onClick: () -> Unit) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xl),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = spacing.xl, vertical = spacing.lg)
    ) {
        Box(
            modifier = Modifier.size(ICON_TILE).background(c.surfaceAlt, RectangleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(AppR.drawable.ic_vote_message_chat),
                contentDescription = null,
                tint = c.text,
                modifier = Modifier.size(ICON)
            )
        }
        BasicText(
            text = stringResource(AppR.string.coinVote_proposalDetail_viewForumDiscussion),
            style = ZappTheme.typography.rowTitle.copy(color = c.text),
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(DesignR.drawable.ic_chevron_right),
            contentDescription = null,
            tint = c.textMuted,
            modifier = Modifier.size(ICON)
        )
    }
}

@Composable
private fun VoteOptions(options: List<VoteVoteOptionRowState>) {
    val c = ZappTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            VoteOptionRow(option)
            if (index < options.lastIndex) {
                Box(modifier = Modifier.fillMaxWidth().height(HAIRLINE).background(c.border))
            }
        }
    }
}

@Composable
private fun VoteOptionRow(option: VoteVoteOptionRowState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !option.isLocked) { option.onSelect() }
                .padding(vertical = spacing.lg)
    ) {
        VoteRadioIndicator(isChecked = option.isSelected)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs)
        ) {
            BasicText(
                text = option.label.getValue(),
                style = ZappTheme.typography.rowTitle.copy(color = c.text)
            )
            option.description?.let {
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted)
                )
            }
        }
    }
}

private const val MAX_LINES = 4
private val ICON_TILE = 40.dp
private val ICON = 20.dp
private val HAIRLINE = 1.dp

@PreviewScreens
@Composable
private fun VoteProposalDetailPreview() =
    ProvideZappTheme { VoteProposalDetailView(VoteProposalDetailState.preview) }
