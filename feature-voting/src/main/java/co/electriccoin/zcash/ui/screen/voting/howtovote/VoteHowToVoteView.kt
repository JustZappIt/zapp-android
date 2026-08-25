// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.howtovote

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
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.common.WalletHeaderIcons
import co.electriccoin.zcash.ui.screen.voting.VoteButton
import co.electriccoin.zcash.ui.screen.voting.component.VoteAppBar
import co.electriccoin.zcash.ui.R as AppR
import co.electriccoin.zcash.ui.design.R as DesignR

/** First-run explainer. Continue moves to the bottom bar beside back, where the fork keeps it. */
@Composable
fun VoteHowToVoteView(state: VoteHowToVoteState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = { VoteAppBar(title = stringResource(AppR.string.coinVote_common_screenTitle)) },
        bottomBar = {
            ZappBottomActionBar(
                onBack = state.onBack,
                primaryAction = { VoteButton(state.continueButton) }
            )
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
            WalletHeaderIcons(state = state.walletHeaderIcons)

            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                BasicText(
                    text = state.title.getValue(),
                    style = ZappTheme.typography.sectionTitle.copy(color = c.text)
                )
                state.subtitle?.let {
                    BasicText(
                        text = it.getValue(),
                        style = ZappTheme.typography.body.copy(color = c.textMuted)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.xl)) {
                state.steps.forEach { StepRow(it) }
            }

            state.infoText?.let { info ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        painter = painterResource(DesignR.drawable.ic_info),
                        contentDescription = null,
                        tint = c.textMuted,
                        modifier = Modifier.size(INFO_ICON)
                    )
                    BasicText(
                        text = info.getValue(),
                        style = ZappTheme.typography.caption.copy(color = c.textMuted),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Upstream numbers each step in a filled circle; the fork uses the same square tile as elsewhere. */
@Composable
private fun StepRow(step: VoteStep) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(NUMBER_TILE).background(c.accent, RectangleShape),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = step.number,
                style = ZappTheme.typography.chip.copy(color = c.onAccent, textAlign = TextAlign.Center)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            BasicText(
                text = step.title.getValue(),
                style = ZappTheme.typography.rowTitle.copy(color = c.text)
            )
            BasicText(
                text = step.description.getValue(),
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted)
            )
        }
    }
}

private val NUMBER_TILE = 28.dp
private val INFO_ICON = 16.dp

@PreviewScreens
@Composable
private fun VoteHowToVotePreview() =
    ProvideZappTheme { VoteHowToVoteView(VoteHowToVoteState.preview) }
