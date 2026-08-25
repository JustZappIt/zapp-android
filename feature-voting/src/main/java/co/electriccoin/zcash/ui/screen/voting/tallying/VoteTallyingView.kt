// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.tallying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.voting.component.VoteAppBar
import co.electriccoin.zcash.ui.R as UiR
import co.electriccoin.zcash.ui.design.R as DesignR

/**
 * The wait between a poll closing and its tally being published. Upstream centres the whole column
 * behind a haze; here the page keeps the flat surface and the icon sits in a square accent tile,
 * because the fork has no circles and no frosted layers to hide behind.
 */
@Composable
fun VoteTallyingView(state: VoteTallyingState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = { VoteAppBar(title = stringResource(UiR.string.coinVote_common_governanceTitle)) },
        bottomBar = { ZappBottomActionBar(onBack = state.onBack) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(ICON_TILE).background(c.accentSoft, RectangleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.ic_info),
                    contentDescription = null,
                    tint = c.accentText,
                    modifier = Modifier.size(ICON)
                )
            }

            Spacer(Modifier.size(spacing.xl3))
            BasicText(
                text = stringResource(UiR.string.coinVote_tallying_titleInProgress),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text, textAlign = TextAlign.Center)
            )

            Spacer(Modifier.size(spacing.lg))
            BasicText(
                text = stringResource(UiR.string.coinVote_tallying_bodyInProgress),
                style = ZappTheme.typography.body.copy(color = c.textMuted, textAlign = TextAlign.Center),
                modifier = Modifier.padding(horizontal = spacing.xl4)
            )

            Spacer(Modifier.size(spacing.xl))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SPINNER),
                    color = c.accent,
                    strokeWidth = STROKE
                )
                BasicText(
                    text = stringResource(UiR.string.coinVote_tallying_status),
                    style = ZappTheme.typography.caption.copy(color = c.textMuted)
                )
            }

            Spacer(Modifier.size(spacing.xl3))
            ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                DetailRow(UiR.string.coinVote_tallying_detailRound, state.roundTitle)
                DetailRow(UiR.string.coinVote_tallying_detailEnded, state.endedLabel)
                DetailRow(UiR.string.coinVote_tallying_detailProposals, state.proposalCount)
            }
        }
    }
}

@Composable
private fun DetailRow(
    labelRes: Int,
    value: StringResource,
) {
    val c = ZappTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BasicText(
            text = stringResource(labelRes),
            style = ZappTheme.typography.caption.copy(color = c.textMuted)
        )
        BasicText(
            text = value.getValue(),
            style = ZappTheme.typography.rowSubtitle.copy(color = c.text, textAlign = TextAlign.End)
        )
    }
}

private val ICON_TILE = 72.dp
private val ICON = 32.dp
private val SPINNER = 20.dp
private val STROKE = 2.dp

@PreviewScreens
@Composable
private fun VoteTallyingPreview() = ProvideZappTheme { VoteTallyingView(VoteTallyingState.preview) }
