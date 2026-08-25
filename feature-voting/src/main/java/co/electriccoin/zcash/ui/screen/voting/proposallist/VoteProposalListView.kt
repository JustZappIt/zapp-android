// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.proposallist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.home.common.CommonShimmerLoadingScreen
import co.electriccoin.zcash.ui.screen.voting.VoteButton
import co.electriccoin.zcash.ui.screen.voting.VoteColors
import co.electriccoin.zcash.ui.screen.voting.answerColors
import co.electriccoin.zcash.ui.screen.voting.component.VoteAppBar
import co.electriccoin.zcash.ui.screen.voting.component.VoteViewMoreChip
import co.electriccoin.zcash.ui.screen.voting.component.ZipBadge
import java.text.NumberFormat
import java.util.Locale

@Composable
fun VoteProposalListView(state: VoteProposalListState) {
    val spacing = ZappTheme.spacing
    ProposalListScaffold(state) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            item {
                Column(modifier = Modifier.padding(bottom = spacing.lg)) {
                    when (state.mode) {
                        VoteProposalListMode.VOTING,
                        VoteProposalListMode.VOTED -> VotingHeader(state, state.onViewMore ?: {})

                        VoteProposalListMode.REVIEW -> ReviewHeader()
                    }
                }
            }
            items(state.proposals.orEmpty(), key = { it.id }) { ProposalCard(it) }
        }
    }
}

@Composable
fun VoteProposalListLoadingView(state: VoteProposalListState) {
    ProposalListScaffold(state) { padding ->
        CommonShimmerLoadingScreen(
            shimmerItemsCount = SHIMMER_ITEMS,
            modifier = Modifier.fillMaxSize().padding(padding),
            showDivider = false,
        )
    }
}

@Composable
private fun ProposalListScaffold(
    state: VoteProposalListState,
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = ZappTheme.colors
    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = { VoteAppBar(title = stringResource(R.string.coinVote_common_screenTitle)) },
        bottomBar = {
            ZappBottomActionBar(
                onBack = state.onBack,
                primaryAction = state.ctaButton?.let { { VoteButton(it) } }
            )
        },
        content = content
    )
}

@Composable
private fun VotingHeader(
    state: VoteProposalListState,
    onViewMore: () -> Unit,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            BasicText(
                text = state.roundTitle.getValue(),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
                modifier = Modifier.weight(1f)
            )
            state.snapshotHeight?.let {
                Spacer(Modifier.size(spacing.lg))
                BasicText(
                    text = "#${formatSnapshotHeight(it)}",
                    style = ZappTheme.typography.mono.copy(color = c.textMuted)
                )
            }
        }

        state.metaLine?.let { HeaderMetaLine(it) }

        state.description?.let { description ->
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                BasicText(
                    text = description.getValue(),
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                VoteViewMoreChip(onClick = onViewMore)
            }
        }
    }
}

@Composable
private fun HeaderMetaLine(state: VoteProposalMetaLineState) {
    val c = ZappTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = state.leading.getValue(),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        state.trailing?.let {
            Spacer(Modifier.size(ZappTheme.spacing.md))
            BasicText(
                text = it.getValue(),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ReviewHeader() {
    val c = ZappTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)
    ) {
        BasicText(
            text = stringResource(R.string.coinVote_proposalList_reviewTitle),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text)
        )
        BasicText(
            text = stringResource(R.string.coinVote_proposalList_reviewSubtitle),
            style = ZappTheme.typography.body.copy(color = c.textMuted)
        )
    }
}

@Composable
private fun ProposalCard(state: VoteProposalRowState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    ZappBorderedCard(
        modifier = Modifier.clickable(onClick = state.onClick),
        verticalArrangement = Arrangement.spacedBy(spacing.lg)
    ) {
        state.zipNumber?.let { ZipBadge(label = it.getValue()) }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BasicText(
                text = state.title.getValue(),
                style = ZappTheme.typography.rowTitle.copy(color = c.text)
            )
            val description = state.description.getValue()
            if (description.isNotEmpty()) {
                BasicText(
                    text = description,
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                    maxLines = DESCRIPTION_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        state.voteBadge?.let { YourVoteContainer(it) }
    }
}

/**
 * How this wallet answered. A short answer sits beside its label; a long one wraps beneath it, so
 * the block never squeezes the answer into an ellipsis.
 */
@Composable
private fun YourVoteContainer(badge: VoteVoteBadgeState) {
    val colors = badge.color.answerColors()
    val label = badge.label.getValue()
    val container =
        Modifier
            .fillMaxWidth()
            .background(colors.bg, RectangleShape)
            .padding(horizontal = ZappTheme.spacing.lg, vertical = ZappTheme.spacing.md)

    if (label.length <= YOUR_VOTE_SHORT_LABEL_MAX_CHARS) {
        Row(
            modifier = container,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YourVoteLabel(colors)
            YourVoteValue(label, colors)
        }
    } else {
        Column(modifier = container, verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xxs)) {
            YourVoteLabel(colors)
            YourVoteValue(label, colors)
        }
    }
}

@Composable
private fun YourVoteLabel(colors: VoteColors) =
    BasicText(
        text = stringResource(R.string.coinVote_proposalList_yourVote).uppercase(),
        style = ZappTheme.typography.groupLabel.copy(color = colors.labelColor)
    )

@Composable
private fun YourVoteValue(
    label: String,
    colors: VoteColors,
) = BasicText(text = label, style = ZappTheme.typography.chip.copy(color = colors.textColor))

private fun formatSnapshotHeight(height: Long): String =
    NumberFormat.getNumberInstance(Locale.US).format(height)

private const val YOUR_VOTE_SHORT_LABEL_MAX_CHARS = 10
private const val DESCRIPTION_LINES = 2
private const val SHIMMER_ITEMS = 6

@PreviewScreens
@Composable
private fun VoteProposalListPreview() =
    ProvideZappTheme { VoteProposalListView(VoteProposalListState.preview) }
