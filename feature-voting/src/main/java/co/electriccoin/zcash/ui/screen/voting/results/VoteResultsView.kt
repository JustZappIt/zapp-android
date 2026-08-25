// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.results

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.home.common.CommonShimmerLoadingScreen
import co.electriccoin.zcash.ui.screen.voting.VoteButton
import co.electriccoin.zcash.ui.screen.voting.answerColors
import co.electriccoin.zcash.ui.screen.voting.component.VoteAppBar
import co.electriccoin.zcash.ui.screen.voting.component.VoteViewMoreChip
import co.electriccoin.zcash.ui.screen.voting.component.ZipBadge
import co.electriccoin.zcash.ui.screen.voting.voteBarAction

@Composable
fun VoteResultsView(state: VoteResultsState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    ResultsScaffold(
        onBack = state.onBack,
        primaryAction = { VoteButton(state.doneButton, modifier = voteBarAction()) }
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
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                BasicText(
                    text = state.roundTitle.getValue(),
                    style = ZappTheme.typography.sectionTitle.copy(color = c.text)
                )
                if (state.roundDescription.getValue().isNotEmpty()) {
                    BasicText(
                        text = state.roundDescription.getValue(),
                        style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                        maxLines = DESCRIPTION_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    VoteViewMoreChip(onClick = { state.onViewMore?.invoke() })
                }
            }

            BasicText(
                text = stringResource(R.string.coinVote_results_title),
                style = ZappTheme.typography.rowTitle.copy(color = c.text)
            )

            Column(verticalArrangement = Arrangement.spacedBy(spacing.xl)) {
                state.proposals.forEach { ProposalResultCard(it) }
            }
        }
    }
}

@Composable
fun VoteResultsLoadingView(onBack: () -> Unit) {
    ResultsScaffold(onBack = onBack, primaryAction = null) { padding ->
        CommonShimmerLoadingScreen(
            shimmerItemsCount = SHIMMER_ITEMS,
            modifier = Modifier.fillMaxSize().padding(padding),
            showDivider = false,
        )
    }
}

@Composable
private fun ResultsScaffold(
    onBack: () -> Unit,
    primaryAction: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?,
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
        bottomBar = { ZappBottomActionBar(onBack = onBack, primaryAction = primaryAction) },
        content = content
    )
}

/**
 * Results sit on the alternate surface rather than a bordered card, so a tally reads as a finished
 * record instead of something still asking to be acted on.
 */
@Composable
private fun ProposalResultCard(state: VoteProposalResultState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surfaceAlt, RectangleShape)
                .padding(spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg)
    ) {
        state.zipNumber?.let { ZipBadge(label = it.getValue()) }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BasicText(
                text = state.title.getValue(),
                style = ZappTheme.typography.rowTitle.copy(color = c.text)
            )
            if (state.description.getValue().isNotEmpty()) {
                BasicText(
                    text = state.description.getValue(),
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                    maxLines = DESCRIPTION_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            state.options.forEach { OptionResultBar(it) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.votedLabel?.let {
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.caption.copy(color = c.textSubtle),
                    modifier = Modifier.weight(1f).padding(end = spacing.md),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            } ?: Box(Modifier.weight(1f))
            BasicText(
                text = state.totalZec.getValue(),
                style = ZappTheme.typography.caption.copy(color = c.textSubtle)
            )
        }
    }
}

/** The winning option keeps its answer colour; the rest go grey, so the outcome is the only accent. */
@Composable
private fun OptionResultBar(option: VoteOptionResultState) {
    val c = ZappTheme.colors
    val answer = option.color.answerColors()
    val barColor: Color = if (option.isWinner) answer.labelColor else c.textSubtle
    val textColor: Color = if (option.isWinner) answer.textColor else c.textMuted

    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = option.label.getValue(),
                style = ZappTheme.typography.rowSubtitle.copy(color = textColor),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = option.amountZec.getValue(),
                style = ZappTheme.typography.rowSubtitle.copy(color = textColor),
            )
        }
        LinearProgressIndicator(
            progress = { option.fraction },
            modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
            color = barColor,
            trackColor = c.border,
            gapSize = (-1).dp,
            drawStopIndicator = {},
        )
    }
}

private const val DESCRIPTION_LINES = 2
private const val SHIMMER_ITEMS = 6
private val BAR_HEIGHT = 8.dp

@PreviewScreens
@Composable
private fun VoteResultsPreview() = ProvideZappTheme { VoteResultsView(VoteResultsState.preview) }
