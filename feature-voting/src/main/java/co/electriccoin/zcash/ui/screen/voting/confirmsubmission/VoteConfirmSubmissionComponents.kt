// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.confirmsubmission

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.voting.VoteButton

/**
 * What is about to be submitted, or what is being submitted. Which rows appear depends on the
 * stage: before submitting, a software wallet is told the memo it will carry; once under way, the
 * voting power in flight matters more than the memo, so that row takes its place.
 */
@Composable
internal fun VoteSubmissionDetailsCard(state: VoteConfirmSubmissionState) {
    val c = ZappTheme.colors
    val isIdle = state.status is VoteSubmissionStatus.Idle
    Column(
        modifier = Modifier.fillMaxWidth().background(c.surfaceAlt, RectangleShape)
    ) {
        VoteSubmissionDetailRow(
            label = stringRes(R.string.coinVote_confirmSubmission_detailPoll),
            value = state.roundTitle.getValue(),
        )
        if (isIdle && !state.isKeystoneUser) {
            RowDivider()
            VoteSubmissionDetailRow(
                label = stringRes(R.string.coinVote_confirmSubmission_detailMemo),
                value = state.memo.getValue(),
                valueStyle = ZappTheme.typography.caption,
            )
        } else if (!isIdle) {
            RowDivider()
            VoteSubmissionDetailRow(
                label = stringRes(R.string.coinVote_confirmSubmission_detailVotingPower),
                value = state.votingWeightZEC.getValue(),
            )
        }
    }
}

@Composable
private fun RowDivider() =
    Box(modifier = Modifier.fillMaxWidth().height(HAIRLINE).background(ZappTheme.colors.border))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoteSubmissionDetailRow(
    label: StringResource,
    value: String,
    valueStyle: TextStyle = ZappTheme.typography.rowSubtitle,
) {
    val c = ZappTheme.colors
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ZappTheme.spacing.xl2, vertical = ZappTheme.spacing.lg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs),
    ) {
        BasicText(
            text = label.getValue(),
            style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
        )
        BasicText(text = value, style = valueStyle.copy(color = c.text))
    }
}

@Composable
internal fun VoteSubmissionBottomSection(state: VoteConfirmSubmissionState) {
    val progressTitle: StringResource? =
        when (val status = state.status) {
            is VoteSubmissionStatus.Authorizing -> {
                stringRes(R.string.coinVote_store_submissionAuthorizingVote)
            }

            is VoteSubmissionStatus.Submitting -> {
                stringRes(
                    R.string.coinVote_confirmSubmission_progressSubmittingVoteCount,
                    status.current,
                    status.total
                )
            }

            else -> {
                null
            }
        }

    if (progressTitle == null) {
        VoteButton(state.ctaButton)
    } else {
        VoteSubmissionProgressCard(
            title = progressTitle,
            progress = state.submissionProgress(),
            ctaButton = state.ctaButton
        )
    }
}

private const val DELEGATION_PROGRESS_WEIGHT = 0.3f

private fun VoteConfirmSubmissionState.submissionProgress(): Float {
    val delegationWeight = DELEGATION_PROGRESS_WEIGHT
    return when (val status = status) {
        is VoteSubmissionStatus.Authorizing -> {
            if (includesAuthorizationProgress) {
                status.progress * delegationWeight
            } else {
                status.progress
            }
        }

        is VoteSubmissionStatus.Submitting -> {
            val offset = if (includesAuthorizationProgress) delegationWeight else 0f
            (offset + status.progress * (1f - offset)).coerceIn(0f, 1f)
        }

        else -> {
            0f
        }
    }
}

@Composable
private fun VoteSubmissionProgressCard(
    title: StringResource,
    progress: Float,
    ctaButton: ButtonState,
) {
    val c = ZappTheme.colors
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = PROGRESS_ANIMATION_MS),
        label = "submission_progress"
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.lg)
    ) {
        BasicText(
            text = title.getValue(),
            style = ZappTheme.typography.rowSubtitle.copy(color = c.text),
        )
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
            color = c.accent,
            trackColor = c.border,
            gapSize = (-1).dp,
            drawStopIndicator = {},
        )
        VoteButton(ctaButton, modifier = Modifier.fillMaxWidth())
    }
}

private const val PROGRESS_ANIMATION_MS = 300
private val HAIRLINE = 1.dp
private val BAR_HEIGHT = 8.dp

@PreviewScreens
@Composable
private fun VoteSubmissionDetailsCardPreview() =
    ProvideZappTheme { VoteSubmissionDetailsCard(VoteConfirmSubmissionState.preview) }
