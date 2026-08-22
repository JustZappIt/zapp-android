// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.common.security.PinVerifyOverlay
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappCopyIconButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalance
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappProgressBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepList
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.component.zapp.ZappSuccessHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.gift.model.GiftMessage

@Composable
internal fun GiftCardView(
    state: GiftCardState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing

    // The ready screen puts a bearer secret on the display. Mainnet builds already carry
    // FLAG_SECURE globally; this keeps the link out of screenshots on the builds that do not.
    if (shouldSecureScreen) {
        SecureScreen()
    }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = {
            ZappScreenHeader(
                title = stringResource(R.string.gift_card_title),
                subtitle =
                    stringResource(
                        when (state.stage) {
                            GiftCardStage.DETAILS -> R.string.gift_card_subtitle_details
                            GiftCardStage.PREPARING, GiftCardStage.REVIEW -> R.string.gift_card_subtitle_review
                            GiftCardStage.FUNDING -> R.string.gift_card_subtitle_funding
                            GiftCardStage.READY -> R.string.gift_card_subtitle_ready
                        }
                    ),
                // The way back to a card whose link was never handed out, including one this
                // process never saw. Only offered where the stage has a way out at all.
                right = {
                    state.onOpenSavedCards?.let { onOpen ->
                        ZappSectionLabel(
                            text = stringResource(R.string.gift_card_list_open),
                            color = ZappTheme.colors.accentText,
                            modifier = Modifier.clickable(onClick = onOpen),
                        )
                    }
                },
            )
        },
        bottomBar = { GiftCardBottomBar(state) },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState()),
        ) {
            MintedCardPodium(state)
            when (state.stage) {
                GiftCardStage.DETAILS -> DetailsSection(state)
                GiftCardStage.PREPARING -> ZappScreenProgressIndicator(Modifier.height(240.dp))
                GiftCardStage.REVIEW -> ReviewSection(state)
                GiftCardStage.FUNDING -> FundingSection()
                GiftCardStage.READY -> ReadySection(state)
            }
            state.error?.let { ErrorBanner(it.messageRes()) }
            Spacer(Modifier.height(spacing.xl))
        }
    }

    state.pinVerify?.let { PinVerifyOverlay(state = it) }
}

/** The one button a stage offers, or null where there is nothing to press. */
private data class GiftCardAction(
    @param:StringRes val text: Int,
    val icon: ImageVector? = null,
    val isEnabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun GiftCardBottomBar(state: GiftCardState) {
    val spacing = ZappTheme.spacing
    val sharePickerText = stringResource(R.string.gift_card_ready_share_picker)
    val action =
        when (state.stage) {
            GiftCardStage.DETAILS -> {
                GiftCardAction(
                    text = R.string.gift_card_continue,
                    isEnabled = state.canContinue,
                    onClick = state.onContinue,
                )
            }

            GiftCardStage.REVIEW -> {
                GiftCardAction(
                    text =
                        if (state.isAuthenticating) {
                            R.string.gift_card_authenticating
                        } else {
                            R.string.gift_card_review_confirm
                        },
                    icon = Icons.Default.CardGiftcard,
                    isEnabled = state.canConfirm,
                    onClick = state.onConfirm,
                )
            }

            GiftCardStage.READY -> {
                GiftCardAction(
                    text = R.string.gift_card_ready_share,
                    icon = Icons.Default.Share,
                    onClick = { state.onShare(sharePickerText) },
                )
            }

            // Nothing to press while the card is being minted or broadcast, and no way back.
            GiftCardStage.PREPARING, GiftCardStage.FUNDING -> {
                null
            }
        }

    ZappBottomActionBar(
        onBack = state.onBack,
        isBackEnabled = state.isBackEnabled,
        primaryAction =
            action?.let {
                {
                    ZappButton(
                        text = stringResource(it.text),
                        leadingIcon = it.icon,
                        enabled = it.isEnabled,
                        onClick = it.onClick,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(start = spacing.lg),
                    )
                }
            },
    )
}

@Composable
private fun DetailsSection(state: GiftCardState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    var messageField by remember { mutableStateOf(TextFieldValue(state.message)) }

    ZappGroupHeader(text = stringResource(R.string.gift_card_amount_label))
    Column(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        ZappOfframpHeroAmountField(
            symbol = stringResource(R.string.gift_card_amount_symbol),
            state = state.amount,
            secondaryText = null,
            balance =
                state.spendableBalance?.let {
                    ZappFieldBalance(
                        label = stringResource(R.string.gift_card_amount_balance_label),
                        amount = it.getValue(),
                    )
                },
            isError = state.amount.isError,
        )
        BasicText(
            text = stringResource(R.string.gift_card_amount_hint),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
    }

    ZappGroupHeader(text = stringResource(R.string.gift_card_message_label))
    Column(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        ZappInputField(
            value = messageField,
            onValueChange = {
                messageField = it
                state.onMessageChange(it.text)
            },
            placeholder = stringResource(R.string.gift_card_message_placeholder),
        )
        BasicText(
            // Grapheme clusters, not String.length: one emoji is 2 UTF-16 units and a family emoji
            // is 7, so a code-unit counter would read as nonsense next to what the sender typed.
            text =
                stringResource(
                    R.string.gift_card_message_counter,
                    state.messageGraphemes,
                    GiftMessage.MAX_GRAPHEMES
                ),
            style =
                ZappTheme.typography.caption
                    .copy(color = if (state.messageGraphemes > GiftMessage.MAX_GRAPHEMES) c.danger else c.textSubtle),
        )

        // Expiry lives here rather than in a section of its own: it is advisory, it changes
        // nothing on chain, and almost nobody should set one. See GiftExpiryPicker.
        GiftExpiryPicker(
            expiry = state.expiry,
            enabled = state.stage == GiftCardStage.DETAILS,
            onExpiryChange = state.onExpiryChange,
        )
    }
}

@Composable
private fun ReviewSection(state: GiftCardState) {
    val spacing = ZappTheme.spacing
    val quote = state.quote ?: return

    ZappGroupHeader(text = stringResource(R.string.gift_card_review_label))
    ZappBorderedCard(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        ZappSummaryRow(
            label = stringResource(R.string.gift_card_review_amount),
            value = stringRes(quote.cardAmount).getValue(),
        )
        // Named as a prepayment rather than a fee the sender is charged: it buys the recipient a
        // claim that costs them nothing, which is the whole reason it is on the bill.
        ZappSummaryRow(
            label = stringResource(R.string.gift_card_review_reserve),
            value = stringRes(quote.claimFeeReserve).getValue(),
        )
        ZappSummaryRow(
            label = stringResource(R.string.gift_card_review_network_fee),
            value = stringRes(quote.networkFee).getValue(),
        )
        ZappSummaryRow(
            label = stringResource(R.string.gift_card_review_total),
            value = stringRes(quote.total).getValue(),
            valueColor = ZappTheme.colors.accentText,
        )
        quote.card.message?.let {
            ZappSummaryRow(label = stringResource(R.string.gift_card_review_message_label), value = it)
        }
    }

    ZappGroupHeader(text = stringResource(R.string.gift_card_review_warning_title))
    Column(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        WarningRow(R.string.gift_card_review_warning_irreversible)
        WarningRow(R.string.gift_card_review_warning_bearer)
        WarningRow(R.string.gift_card_review_warning_backup)
        WarningRow(R.string.gift_card_review_warning_delay)
    }
}

/**
 * The card, on the two stages where it exists as an object rather than a form.
 *
 * Hoisted out of the stage `when` on purpose: funding and ready are the same card at two moments,
 * and a podium composed separately per stage would dispose the turning card and start a fresh one
 * from zero at exactly the hand-off the turn is meant to carry through.
 */
@Composable
private fun MintedCardPodium(state: GiftCardState) {
    if (state.stage != GiftCardStage.FUNDING && state.stage != GiftCardStage.READY) return
    GiftCardPodium(
        amount = state.quote?.let { stringRes(it.cardAmount) },
        tier = giftCardTier(state.quote?.cardAmount?.value ?: 0L, isSettled = false),
        isSettled = state.stage == GiftCardStage.READY,
        fiat = state.fiat,
    )
}

@Composable
private fun FundingSection() {
    val spacing = ZappTheme.spacing
    Column(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.xl),
    ) {
        // Nothing here can be measured — a broadcast lands when it lands — so the bar sweeps
        // rather than inventing a percentage for it.
        ZappProgressBar(fraction = null, label = stringResource(R.string.gift_card_funding_note))
        ZappStepList(
            steps =
                listOf(
                    ZappStep(
                        label = stringRes(R.string.gift_card_step_minted),
                        status = ZappStepStatus.Completed,
                    ),
                    ZappStep(
                        label = stringRes(R.string.gift_card_step_funding),
                        status = ZappStepStatus.InProgress,
                    ),
                    ZappStep(
                        label = stringRes(R.string.gift_card_step_ready),
                        status = ZappStepStatus.Pending,
                    ),
                )
        )
    }
}

@Composable
private fun ReadySection(state: GiftCardState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    val link = state.link ?: return

    ZappSuccessHeader(
        title = stringRes(R.string.gift_card_ready_title),
        subtitle = stringRes(R.string.gift_card_ready_subtitle),
        modifier = Modifier.padding(horizontal = spacing.xl),
    )

    ZappGroupHeader(text = stringResource(R.string.gift_card_ready_link_label))
    ZappBorderedCard(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                text = link,
                style = ZappTheme.typography.mono.copy(color = c.text),
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag(GiftCardTag.LINK),
            )
            ZappCopyIconButton(
                isCopied = state.isCopied,
                contentDescription = stringResource(R.string.gift_card_ready_copy),
                onClick = state.onCopy,
            )
        }
    }

    Column(
        modifier = Modifier.padding(horizontal = spacing.xl, vertical = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        WarningRow(R.string.gift_card_ready_bearer)
        WarningRow(R.string.gift_card_ready_claimable)
        ZappButton(
            text = stringResource(R.string.gift_card_ready_done),
            variant = ZappButtonVariant.Ghost,
            onClick = state.onDone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WarningRow(
    @StringRes message: Int,
) {
    val c = ZappTheme.colors
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .width(2.dp)
                    .height(20.dp)
                    .background(c.accent),
        )
        BasicText(
            text = stringResource(message),
            style = ZappTheme.typography.body.copy(color = c.text),
            modifier = Modifier.padding(start = ZappTheme.spacing.lg),
        )
    }
}

// A lookup table, not a decision: one arm per case, no nesting, and exhaustive so a new error
// cannot be added without landing here. Same reasoning as `SubmitResultFold`.
@Suppress("CyclomaticComplexMethod")
@StringRes
private fun GiftCardError.messageRes(): Int =
    when (this) {
        GiftCardError.AMOUNT_INVALID -> R.string.gift_card_amount_error_too_small
        GiftCardError.MESSAGE_TOO_LONG -> R.string.gift_card_message_error_too_long
        GiftCardError.INSUFFICIENT_FUNDS -> R.string.gift_card_error_insufficient
        GiftCardError.KEYSTONE_UNSUPPORTED -> R.string.gift_card_error_keystone
        GiftCardError.UNSUPPORTED_NETWORK -> R.string.gift_card_error_network
        GiftCardError.CHAIN_TIP_UNAVAILABLE -> R.string.gift_card_error_chain_tip
        GiftCardError.PERSIST_FAILED -> R.string.gift_card_error_persist
        GiftCardError.MINT_FAILED -> R.string.gift_card_error_mint
        GiftCardError.PROPOSAL_FAILED -> R.string.gift_card_error_proposal
        GiftCardError.AUTHENTICATION_FAILED -> R.string.gift_card_error_auth
        GiftCardError.SUBMIT_REJECTED -> R.string.gift_card_error_submit
        GiftCardError.SUBMIT_UNCERTAIN -> R.string.gift_card_error_submit_uncertain
        GiftCardError.SHARE_FAILED -> R.string.gift_card_error_share
        GiftCardError.HANDOFF_FAILED -> R.string.gift_card_error_handoff
    }

internal object GiftCardTag {
    const val LINK = "gift_card_link"
}

@PreviewScreens
@Composable
private fun GiftCardPreview() =
    ProvideZappTheme {
        GiftCardView(
            state =
                GiftCardState(
                    stage = GiftCardStage.DETAILS,
                    amount = NumberTextFieldState(onValueChange = {}),
                    spendableBalance = stringRes(Zatoshi(0)),
                    message = "",
                    messageGraphemes = 0,
                    expiry = GiftExpiry.NEVER,
                    quote = null,
                    fiat = null,
                    link = null,
                    isCopied = false,
                    isAuthenticating = false,
                    error = null,
                    pinVerify = null,
                    onAmountChange = {},
                    onMessageChange = {},
                    onExpiryChange = {},
                    onContinue = {},
                    onConfirm = {},
                    onCopy = {},
                    onShare = {},
                    onDone = {},
                    onBack = {},
                    onOpenSavedCards = null,
                )
        )
    }
