// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.confirmsubmission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenProgressIndicator
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.WalletHeaderBadgeChrome
import co.electriccoin.zcash.ui.screen.common.WalletHeaderIcons
import co.electriccoin.zcash.ui.screen.common.WalletHeaderIconsState
import co.electriccoin.zcash.ui.screen.voting.VoteConfirmationBottomSheet
import co.electriccoin.zcash.ui.screen.voting.component.VoteAppBar
import co.electriccoin.zcash.ui.screen.voting.voteBarAction
import co.electriccoin.zcash.ui.screen.voting.votingerror.VotingErrorMapper

/**
 * The last screen before votes leave the device, and the progress report once they do. Back is
 * disabled while a submission is in flight — the bar keeps the arrow in place but inert, rather
 * than removing it, so the page does not reflow mid-submission.
 */
@Composable
fun VoteConfirmSubmissionView(state: VoteConfirmSubmissionState) {
    VoteConfirmationBottomSheet(state = state.errorSheet)

    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = { VoteAppBar(title = navTitle(state.status).getValue()) },
        bottomBar = {
            ZappBottomActionBar(
                onBack = state.onBack,
                isBackEnabled = !state.status.isInFlight(),
                primaryAction = { VoteSubmissionBottomSection(state, modifier = voteBarAction()) }
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
            verticalArrangement = Arrangement.spacedBy(spacing.xl3)
        ) {
            HeaderSection(state)
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xl)) {
                VoteSubmissionDetailsCard(state)
                if (state.status.isInFlight()) {
                    BasicText(
                        text = stringResource(R.string.coinVote_confirmSubmission_headerSubtitleSubmitting),
                        style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                    )
                }
            }
        }
    }
}

@Composable
fun VoteConfirmSubmissionLoadingView() {
    val c = ZappTheme.colors
    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = { VoteAppBar(title = stringResource(R.string.coinVote_common_submission)) },
    ) { padding ->
        ZappScreenProgressIndicator(modifier = Modifier.padding(padding))
    }
}

@Composable
private fun HeaderSection(state: VoteConfirmSubmissionState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        WalletHeaderIcons(
            state =
                WalletHeaderIconsState(
                    isKeystone = state.isKeystoneUser,
                    badgeIcon =
                        if (state.status is VoteSubmissionStatus.Completed) {
                            R.drawable.ic_vote_check_verified_solid
                        } else {
                            R.drawable.ic_vote_thumbs_up
                        },
                    badgeChrome =
                        if (state.status is VoteSubmissionStatus.Completed) {
                            WalletHeaderBadgeChrome.Success
                        } else {
                            WalletHeaderBadgeChrome.Neutral
                        }
                )
        )
        Spacer(Modifier.height(ZappTheme.spacing.xl3))
        BasicText(
            text = headerTitle(state.status).getValue(),
            style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
        )
        Spacer(Modifier.height(ZappTheme.spacing.md))
        BasicText(
            text = headerSubtitle(state).getValue(),
            style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
        )
    }
}

private fun navTitle(status: VoteSubmissionStatus): StringResource =
    when (status) {
        is VoteSubmissionStatus.Idle -> stringRes(R.string.coinVote_common_confirmation)
        else -> stringRes(R.string.coinVote_common_submission)
    }

private fun headerTitle(status: VoteSubmissionStatus): StringResource =
    when (status) {
        is VoteSubmissionStatus.Idle -> {
            stringRes(R.string.coinVote_confirmSubmission_headerTitleIdle)
        }

        is VoteSubmissionStatus.LocalAuthorizing -> {
            stringRes(R.string.coinVote_store_submissionAuthorizingVote)
        }

        is VoteSubmissionStatus.Authorizing, is VoteSubmissionStatus.Submitting -> {
            stringRes(R.string.coinVote_submission_continuedProcessingTitle)
        }

        is VoteSubmissionStatus.Completed -> {
            stringRes(R.string.coinVote_confirmSubmission_headerTitleCompleted)
        }

        is VoteSubmissionStatus.LocalAuthFailed -> {
            stringRes(R.string.coinVote_confirmSubmission_authorizationFailedTitle)
        }

        is VoteSubmissionStatus.ProtocolAuthFailed -> {
            stringRes(R.string.coinVote_confirmSubmission_authorizationFailedTitle)
        }

        is VoteSubmissionStatus.SubmissionFailed -> {
            stringRes(R.string.coinVote_confirmSubmission_submissionFailedTitle)
        }
    }

private fun headerSubtitle(state: VoteConfirmSubmissionState): StringResource =
    when (val status = state.status) {
        is VoteSubmissionStatus.Idle -> {
            if (state.isKeystoneUser) {
                stringRes(R.string.coinVote_confirmSubmission_headerSubtitleIdleKeystone)
            } else {
                stringRes(R.string.coinVote_confirmSubmission_headerSubtitleIdle)
            }
        }

        is VoteSubmissionStatus.LocalAuthorizing,
        is VoteSubmissionStatus.Authorizing,
        is VoteSubmissionStatus.Submitting -> {
            stringRes(R.string.coinVote_confirmSubmission_headerSubtitleSubmitting)
        }

        is VoteSubmissionStatus.Completed -> {
            stringRes(R.string.coinVote_confirmSubmission_headerSubtitleCompleted)
        }

        is VoteSubmissionStatus.LocalAuthFailed -> {
            stringRes(R.string.coinVote_confirmSubmission_authorizationFailedMessage)
        }

        is VoteSubmissionStatus.ProtocolAuthFailed -> {
            stringRes(R.string.coinVote_confirmSubmission_authorizationFailedMessage)
        }

        is VoteSubmissionStatus.SubmissionFailed -> {
            status.error.toMessageOrDefault(
                status.defaultError ?: stringRes(R.string.coinVote_confirmSubmission_submissionFailedMessage)
            )
        }
    }

private fun String?.toMessageOrDefault(default: StringResource): StringResource =
    if (isNullOrBlank()) {
        default
    } else {
        VotingErrorMapper.toUserFriendlyMessage(this)
    }

private fun previewState(status: VoteSubmissionStatus) =
    VoteConfirmSubmissionState.preview.copy(status = status)

@PreviewScreens
@Composable
private fun ConfirmSubmissionPreviewIdle() =
    ProvideZappTheme { VoteConfirmSubmissionView(previewState(VoteSubmissionStatus.Idle)) }

@PreviewScreens
@Composable
private fun ConfirmSubmissionPreviewAuthorizing() =
    ProvideZappTheme { VoteConfirmSubmissionView(previewState(VoteSubmissionStatus.Authorizing(0.45f))) }

@PreviewScreens
@Composable
private fun ConfirmSubmissionPreviewSubmitting() =
    ProvideZappTheme { VoteConfirmSubmissionView(previewState(VoteSubmissionStatus.Submitting(5, 11, 0.45f))) }

@PreviewScreens
@Composable
private fun ConfirmSubmissionPreviewCompleted() =
    ProvideZappTheme { VoteConfirmSubmissionView(previewState(VoteSubmissionStatus.Completed)) }

@PreviewScreens
@Composable
private fun ConfirmSubmissionPreviewFailed() =
    ProvideZappTheme {
        VoteConfirmSubmissionView(
            previewState(VoteSubmissionStatus.SubmissionFailed("Network error. Please try again."))
        )
    }
