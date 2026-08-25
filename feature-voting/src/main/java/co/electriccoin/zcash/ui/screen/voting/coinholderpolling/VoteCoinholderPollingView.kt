// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.coinholderpolling

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.voting.SessionStatus
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappChipVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappStatusChip
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.home.common.CommonShimmerLoadingScreen
import co.electriccoin.zcash.ui.screen.voting.component.VoteAppBar
import co.electriccoin.zcash.ui.screen.voting.component.VoteTrustIndicatorView

@Composable
fun VoteCoinholderPollingView(state: VoteCoinholderPollingState) {
    ZashiConfirmationBottomSheet(state = state.configErrorSheet)
    ZashiConfirmationBottomSheet(state = state.unverifiedPollWarningSheet)
    ZashiConfirmationBottomSheet(state = state.noRoundsSheet)

    PollingScaffold(state) { padding ->
        val activeRounds = state.activeRounds.orEmpty()
        val pastRounds = state.pastRounds.orEmpty()
        if (activeRounds.isEmpty() && pastRounds.isEmpty()) {
            PollsLoading(padding)
        } else {
            val spacing = ZappTheme.spacing
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                items(activeRounds, key = { it.roundId }, contentType = { POLL_CARD }) { PollCard(it) }
                items(pastRounds, key = { it.roundId }, contentType = { POLL_CARD }) { PollCard(it) }
            }
        }
    }
}

@Composable
fun VoteCoinholderPollingLoadingView(state: VoteCoinholderPollingState) {
    PollingScaffold(state) { padding -> PollsLoading(padding) }
}

@Composable
private fun PollingScaffold(
    state: VoteCoinholderPollingState,
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
        topBar = {
            VoteAppBar(
                title = stringResource(R.string.coinVote_common_screenTitle),
                onConfigSettings = state.onConfigSettings
            )
        },
        bottomBar = { ZappBottomActionBar(onBack = state.onBack) },
        content = content
    )
}

@Composable
private fun PollsLoading(padding: PaddingValues) =
    CommonShimmerLoadingScreen(
        shimmerItemsCount = SHIMMER_ITEMS,
        modifier = Modifier.fillMaxSize().padding(padding),
        showDivider = false,
    )

/**
 * One poll. The reading order is upstream's — where it stands and when it closes, what it asks,
 * then who vouches for it and what you can do — but every surface here is square and the palette
 * is the fork's, so a poll sits in the same visual grammar as a gift card or a settlement row.
 */
@Composable
private fun PollCard(state: VotePollCardState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(state.status)
            Spacer(Modifier.weight(1f))
            BasicText(
                text = state.dateLabel.getValue(),
                style = ZappTheme.typography.caption.copy(color = c.textMuted)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BasicText(
                text = state.title.getValue(),
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
                maxLines = TITLE_LINES,
                overflow = TextOverflow.Ellipsis
            )
            val description = state.description.getValue()
            if (description.isNotEmpty()) {
                BasicText(
                    text = description,
                    style = ZappTheme.typography.body.copy(color = c.textMuted),
                    maxLines = DESCRIPTION_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            state.trustIndicator?.let { VoteTrustIndicatorView(it) } ?: Spacer(Modifier.weight(1f))
            PollActionButton(state)
        }
    }
}

@Composable
private fun PollActionButton(state: VotePollCardState) {
    val label =
        when (state.status) {
            VotePollCardStatus.ACTIVE -> R.string.coinVote_pollsList_enterPoll
            VotePollCardStatus.VOTED -> R.string.coinVote_proposalList_ctaReviewAnswers
            VotePollCardStatus.CLOSED -> R.string.coinVote_common_viewResults
        }
    ZappButton(
        text = stringResource(label),
        variant =
            if (state.status == VotePollCardStatus.ACTIVE) {
                ZappButtonVariant.Primary
            } else {
                ZappButtonVariant.Secondary
            },
        enabled = state.isActionEnabled,
        onClick = state.onAction
    )
}

/**
 * Upstream tints voted the same green as active; here voted reads as done (success) and active as
 * something still wanting attention (accent), so the two are told apart at a glance in a list.
 */
@Composable
private fun StatusChip(status: VotePollCardStatus) {
    val c = ZappTheme.colors
    val (labelRes, variant, dot) =
        when (status) {
            VotePollCardStatus.ACTIVE -> {
                Triple(R.string.coinVote_pollsList_statusActive, ZappChipVariant.Accent, c.accent)
            }

            VotePollCardStatus.VOTED -> {
                Triple(R.string.coinVote_common_voted, ZappChipVariant.Success, c.success)
            }

            VotePollCardStatus.CLOSED -> {
                Triple(R.string.coinVote_pollsList_statusClosed, ZappChipVariant.Muted, c.textSubtle)
            }
        }
    ZappStatusChip(text = stringResource(labelRes), variant = variant, dotColor = dot)
}

private const val POLL_CARD = "pollcard"
private const val SHIMMER_ITEMS = 8
private const val TITLE_LINES = 2
private const val DESCRIPTION_LINES = 3

@PreviewScreens
@Composable
private fun VoteCoinholderPollingPreview() =
    ProvideZappTheme {
        VoteCoinholderPollingView(
            state =
                VoteCoinholderPollingState.preview.copy(
                    activeRounds = listOf(VotePollCardState.preview),
                    pastRounds =
                        listOf(
                            VotePollCardState.preview.copy(
                                roundId = "preview-round-2",
                                status = VotePollCardStatus.CLOSED,
                                sessionStatus = SessionStatus.ACTIVE,
                            )
                        )
                )
        )
    }
