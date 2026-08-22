// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappProgressBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun GiftClaimView(
    state: GiftClaimState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = {
            ZappScreenHeader(
                title = stringResource(R.string.gift_claim_title),
                subtitle = stringResource(state.stage.subtitleRes()),
            )
        },
        bottomBar = { GiftClaimBottomBar(state) },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState()),
            // Centred rather than stacked at the top: on every stage here the card is the subject,
            // and a card pinned under the header with a screen of void beneath it is a form.
            verticalArrangement = Arrangement.Center,
        ) {
            when (state.stage) {
                GiftClaimStage.LOADING -> ZappScreenProgressIndicator(Modifier.height(240.dp))
                GiftClaimStage.PREVIEW -> PreviewSection(state)
                GiftClaimStage.CONSENT -> ConsentSection(state)
                GiftClaimStage.CLAIMING -> ClaimingSection(state)
                GiftClaimStage.DONE -> OutcomeSection(state)
                GiftClaimStage.PENDING_CONFIRMATIONS -> OutcomeSection(state)
                GiftClaimStage.EMPTY -> OutcomeSection(state)
            }
            state.error?.let { ErrorBanner(it.messageRes()) }
            Spacer(Modifier.height(spacing.xl))
        }
    }
}

@Composable
private fun GiftClaimBottomBar(state: GiftClaimState) {
    val spacing = ZappTheme.spacing
    ZappBottomActionBar(
        onBack = state.onBack,
        isBackEnabled = state.isBackEnabled,
        primaryAction =
            when (state.stage) {
                GiftClaimStage.PREVIEW -> {
                    {
                        // Without a loaded card there is nothing to claim, so the button has to be
                        // a way forward rather than a no-op.
                        ZappButton(
                            text =
                                stringResource(
                                    when {
                                        state.isLoaded -> R.string.gift_claim_claim
                                        state.isRetryable -> R.string.gift_claim_retry
                                        else -> R.string.gift_claim_done
                                    }
                                ),
                            onClick =
                                when {
                                    state.isLoaded -> state.onClaim
                                    state.isRetryable -> state.onRetry
                                    else -> state.onBack
                                },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(start = spacing.lg),
                        )
                    }
                }

                GiftClaimStage.CONSENT -> {
                    {
                        ZappButton(
                            text = stringResource(R.string.gift_claim_consent_confirm),
                            onClick = state.onConsent,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(start = spacing.lg),
                        )
                    }
                }

                GiftClaimStage.DONE -> {
                    {
                        ZappButton(
                            text = stringResource(R.string.gift_claim_done),
                            onClick = state.onBack,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(start = spacing.lg),
                        )
                    }
                }

                // Nothing to press while reading the link or mid-claim, nothing to retry on an
                // empty card, and nothing to do while confirmations accrue but wait — that screen
                // re-checks itself rather than asking the recipient to keep tapping.
                GiftClaimStage.LOADING,
                GiftClaimStage.CLAIMING,
                GiftClaimStage.PENDING_CONFIRMATIONS,
                GiftClaimStage.EMPTY,
                -> {
                    null
                }
            },
    )
}

@Composable
private fun PreviewSection(state: GiftClaimState) {
    val spacing = ZappTheme.spacing
    GiftCardPodium(
        amount = state.amountText,
        tier = giftCardTier(state.amount?.value ?: 0L, isSettled = false),
        isSettled = false,
        caption = stringResource(R.string.gift_claim_podium_caption),
        fiat = state.fiat,
        message = state.message,
    )
    if (state.message != null || state.expiry != null) {
        ZappBorderedCard(
            modifier = Modifier.padding(horizontal = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            // Printed on the card too, but clipped to two lines there. A note can run to 128
            // graphemes, and the recipient has to be able to read all of whatever was written.
            state.message?.let {
                ZappSummaryRow(label = stringResource(R.string.gift_claim_message_label), value = it)
            }
            state.expiry?.let { expiry ->
                ZappSummaryRow(
                    label = stringResource(R.string.gift_card_review_expiry_label),
                    value = expiry.date.getValue(),
                )
            }
        }
    }
    // An expiry is advisory: nothing on chain enforces it, so a card past its date still claims.
    if (state.expiry?.isPast == true) Caption(R.string.gift_claim_expired_note)
}

@Composable
private fun ConsentSection(state: GiftClaimState) {
    val spacing = ZappTheme.spacing
    val blocks = state.blocksToScan ?: 0L
    ZappGroupHeader(text = stringResource(R.string.gift_claim_consent_title))
    Column(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        BasicText(
            // The block count *and* a rough duration: "137,000 blocks" means nothing to a
            // recipient deciding whether to leave the app open (§3.6).
            text = stringResource(R.string.gift_claim_consent_body, blocks.toString(), blocks.roughDuration()),
            style = ZappTheme.typography.body.copy(color = ZappTheme.colors.text),
        )
        BasicText(
            text = state.amountText?.getValue().orEmpty(),
            style = ZappTheme.typography.display.copy(color = ZappTheme.colors.text),
        )
    }
}

@Composable
private fun ClaimingSection(state: GiftClaimState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    GiftCardPodium(
        amount = state.amountText,
        tier = giftCardTier(state.amount?.value ?: 0L, isSettled = false),
        // Still turning: the scan is in flight, and the bar below is what reports the wait. The
        // sender's funding screen behaves the same way at the same point.
        isSettled = false,
        caption = stringResource(R.string.gift_claim_podium_caption),
        fiat = state.fiat,
        message = state.message,
    )
    Column(
        modifier = Modifier.padding(horizontal = spacing.xl, vertical = spacing.xl2),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        BasicText(
            text = stringResource(R.string.gift_claim_progress_syncing),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text),
        )
        ZappProgressBar(
            // Null until the scan reports a real figure, so the bar sweeps rather than sitting at
            // a zero that looks stuck. The first stretch of a claim genuinely has nothing to say:
            // the SDK is still resolving the card's start height.
            fraction = state.progressFraction,
            label = stringResource(R.string.gift_claim_progress_note),
            detail = state.blocksRemaining?.let { stringResource(R.string.gift_claim_progress_remaining, it) },
        )
    }
}

@PreviewScreens
@Composable
private fun GiftClaimPreview() =
    ProvideZappTheme {
        GiftClaimView(
            state =
                GiftClaimState(
                    stage = GiftClaimStage.PREVIEW,
                    amount = Zatoshi(10_000L),
                    fiat = stringRes("$0.01"),
                    message = "happy birthday",
                    expiry = null,
                    blocksToScan = null,
                    progressFraction = 0.42f,
                    blocksRemaining = 12_340L,
                    confirmations = null,
                    requiredConfirmations = 10,
                    error = null,
                    onClaim = {},
                    onConsent = {},
                    onRetry = {},
                    onBack = {},
                )
        )
    }
