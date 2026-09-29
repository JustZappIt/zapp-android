// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalance
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappProgressBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegment
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegmentedSelector
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappSuccessHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappValueCard
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdScaffold

@Composable
internal fun PrivateUsdSendView(state: PrivateUsdSendState) {
    PrivateUsdScaffold(
        title =
            stringResource(
                if (state.isWithdrawal) R.string.private_usd_withdraw_title else R.string.private_usd_send_title
            ),
        info = state.info,
        onBack = state.onBack,
        isBackEnabled = !state.isBusy && state.phase != PrivateUsdSendPhase.SENDING,
        primaryButton = state.primaryButton,
        error = state.error,
    ) {
        when (state.phase) {
            PrivateUsdSendPhase.FORM -> Form(state)
            PrivateUsdSendPhase.REVIEW -> Review(state)
            PrivateUsdSendPhase.SENDING -> Sending(state)
            PrivateUsdSendPhase.DONE -> Done(state)
        }
    }
}

@Composable
private fun Form(state: PrivateUsdSendState) {
    ZappSegmentedSelector(
        segments =
            listOf(
                ZappSegment(stringResource(R.string.private_usd_send_mode_private)),
                ZappSegment(stringResource(R.string.private_usd_send_mode_withdraw)),
            ),
        selectedIndex = if (state.isWithdrawal) 1 else 0,
        onSelect = state.onModeSelect,
    )
    if (state.assets.size > 1) {
        Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
            ZappSectionLabel(text = stringResource(R.string.private_usd_send_asset))
            ZappSegmentedSelector(
                segments = state.assets.map { ZappSegment(it) },
                selectedIndex = state.selectedAsset,
                onSelect = state.onAssetSelect,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        ZappSectionLabel(text = stringResource(R.string.private_usd_send_amount))
        ZappOfframpHeroAmountField(
            symbol = "$",
            state = state.amount,
            secondaryText = state.amountNote?.getValue(),
            isError = state.isAmountInvalid,
            balance =
                state.available?.let {
                    ZappFieldBalance(
                        label = stringResource(R.string.private_usd_send_available),
                        amount = it.getValue(),
                        onClick = state.onMax,
                    )
                },
        )
    }
    Recipient(state)
}

@Composable
private fun Recipient(state: PrivateUsdSendState) {
    var value by remember { mutableStateOf(TextFieldValue(state.recipient)) }
    LaunchedEffect(state.recipient) {
        if (state.recipient != value.text) value = TextFieldValue(state.recipient, TextRange(state.recipient.length))
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        ZappSectionLabel(
            text =
                stringResource(
                    if (state.isWithdrawal) {
                        R.string.private_usd_send_to_withdraw
                    } else {
                        R.string.private_usd_send_to_private
                    }
                ),
        )
        ZappInputField(
            value = value,
            onValueChange = {
                value = it
                state.onRecipientChange(it.text)
            },
            placeholder =
                stringResource(
                    if (state.isWithdrawal) {
                        R.string.private_usd_send_hint_withdraw
                    } else {
                        R.string.private_usd_send_hint_private
                    }
                ),
        )
        state.recipientError?.let {
            BasicText(
                text = it.getValue(),
                style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.danger),
            )
        }
    }
}

@Composable
private fun Review(state: PrivateUsdSendState) {
    val c = ZappTheme.colors
    Title(
        if (state.isWithdrawal) {
            R.string.private_usd_send_review_title_withdraw
        } else {
            R.string.private_usd_send_review_title_private
        }
    )
    state.review?.let { review ->
        ZappSettlementLedger(
            rows =
                listOfNotNull(
                    ZappSettlementLedgerRow(
                        stringResource(R.string.private_usd_send_review_amount),
                        review.amount.getValue(),
                    ),
                    review.railgunFee?.let {
                        ZappSettlementLedgerRow(
                            stringResource(R.string.private_usd_send_review_railgun_fee),
                            it.getValue(),
                        )
                    },
                    ZappSettlementLedgerRow(
                        stringResource(R.string.private_usd_send_review_network_fee),
                        review.networkFee.getValue(),
                    ),
                    ZappSettlementLedgerRow(
                        stringResource(R.string.private_usd_send_review_receives),
                        review.receives.getValue(),
                    ),
                ),
            notice = stringResource(R.string.private_usd_send_withdraw_public).takeIf { state.isWithdrawal },
        )
        ZappValueCard(
            value = review.to,
            label = stringResource(R.string.private_usd_send_review_to),
            gutter = 0.dp,
        )
        if (review.paidByTestAccount) {
            BasicText(
                text = stringResource(R.string.private_usd_send_review_test_note),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
        }
    }
}

@Composable
private fun Sending(state: PrivateUsdSendState) {
    Title(R.string.private_usd_send_proving)
    BasicText(
        text = stringResource(R.string.private_usd_send_proving_detail),
        style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
    )
    ZappProgressBar(fraction = state.proofProgress)
}

@Composable
private fun Done(state: PrivateUsdSendState) {
    val uriHandler = LocalUriHandler.current
    state.done?.let { done ->
        ZappSuccessHeader(
            title =
                stringRes(
                    if (state.isWithdrawal) {
                        R.string.private_usd_send_done_title_withdraw
                    } else {
                        R.string.private_usd_send_done_title_private
                    }
                ),
            subtitle = done.body,
        )
        done.explorerUrl?.let { url ->
            ZappButton(
                text = stringResource(R.string.private_usd_send_view_tx),
                variant = ZappButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
                onClick = { uriHandler.openUri(url) },
            )
        }
    }
}

@Composable
private fun Title(
    @StringRes text: Int
) {
    BasicText(
        text = stringResource(text),
        style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
    )
}

@PreviewScreens
@Composable
private fun FormPreview() =
    ZcashTheme {
        PrivateUsdSendView(
            state =
                PrivateUsdSendState(
                    phase = PrivateUsdSendPhase.FORM,
                    isWithdrawal = true,
                    onModeSelect = {},
                    assets = listOf("tUSD", "USDC"),
                    selectedAsset = 0,
                    onAssetSelect = {},
                    amount = NumberTextFieldState(NumberTextFieldInnerState()) {},
                    amountNote = null,
                    isAmountInvalid = false,
                    available = stringRes("$12.34"),
                    onMax = {},
                    recipient = "",
                    onRecipientChange = {},
                    recipientError = null,
                    review = null,
                    proofProgress = null,
                    done = null,
                    error = null,
                    info = PrivateUsdInfo(title = stringRes("Withdrawing makes it public")),
                    primaryButton = ButtonState(stringRes("Review"), isEnabled = false),
                    onBack = {},
                ),
        )
    }
