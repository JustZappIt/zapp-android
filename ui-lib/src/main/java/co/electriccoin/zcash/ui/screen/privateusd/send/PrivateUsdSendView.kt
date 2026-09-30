// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.security.PinVerifyOverlay
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalance
import co.electriccoin.zcash.ui.design.component.zapp.ZappFieldBalanceAction
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
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdScaffold

@Composable
internal fun PrivateUsdSendView(state: PrivateUsdSendState) {
    PrivateUsdScaffold(
        title = stringResource(state.mode.title),
        info = state.info,
        onBack = state.onBack,
        isBackEnabled = state.isBackEnabled,
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
    state.pinVerify?.let { PinVerifyOverlay(state = it) }
}

@Composable
private fun Form(state: PrivateUsdSendState) {
    ZappSegmentedSelector(
        segments = PrivateUsdSendMode.entries.map { ZappSegment(stringResource(it.title)) },
        selectedIndex = state.mode.ordinal,
        onSelect = { state.onModeSelect(PrivateUsdSendMode.entries[it]) },
    )
    if (state.assets.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
            ZappSectionLabel(text = stringResource(R.string.private_usd_send_asset))
            ZappSegmentedSelector(
                segments = state.assets.map { ZappSegment(it.symbol) },
                selectedIndex = state.assets.indexOfFirst { it.isSelected },
                onSelect = { state.assets[it].onSelect() },
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        ZappSectionLabel(text = stringResource(R.string.private_usd_send_amount))
        ZappOfframpHeroAmountField(
            symbol = state.currencySymbol,
            state = state.amount,
            secondaryText = state.amountNote?.getValue(),
            isError = state.isAmountInvalid,
            balance =
                state.available?.let {
                    ZappFieldBalance(
                        label = stringResource(R.string.private_usd_row_available),
                        amount = it.getValue(),
                        action =
                            ZappFieldBalanceAction(
                                onClickLabel = stringResource(R.string.private_usd_use_available),
                                onClick = state.onMax,
                            ),
                    )
                },
        )
    }
    Recipient(state.recipient, state.mode)
}

@Composable
private fun Recipient(
    recipient: TextFieldState,
    mode: PrivateUsdSendMode,
) {
    val text = recipient.value.getValue()
    var value by remember { mutableStateOf(TextFieldValue(text)) }
    LaunchedEffect(text) {
        if (text != value.text) value = TextFieldValue(text, TextRange(text.length))
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        ZappSectionLabel(text = stringResource(mode.recipientLabel))
        ZappInputField(
            value = value,
            onValueChange = {
                value = it
                recipient.onValueChange(it.text)
            },
            placeholder = stringResource(mode.recipientHint),
            keyboardOptions = ADDRESS_KEYBOARD,
            enabled = recipient.isEnabled,
        )
        recipient.error?.let {
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
    Title(state.mode.reviewTitle)
    state.review?.let { review ->
        ZappSettlementLedger(
            rows =
                listOfNotNull(
                    ZappSettlementLedgerRow(stringResource(R.string.private_usd_send_asset), review.token),
                    ZappSettlementLedgerRow(stringResource(R.string.private_usd_send_amount), review.amount.getValue()),
                    review.railgunFee?.let { ZappSettlementLedgerRow(it.label.getValue(), it.amount.getValue()) },
                    ZappSettlementLedgerRow(
                        stringResource(R.string.convert_network_fee),
                        stringResource(R.string.private_usd_send_review_test_account),
                    ),
                    ZappSettlementLedgerRow(
                        stringResource(R.string.private_usd_send_review_receives),
                        review.receives.getValue(),
                    ),
                ),
            notice =
                stringResource(R.string.private_usd_send_withdraw_public)
                    .takeIf { state.mode == PrivateUsdSendMode.WITHDRAW },
        )
        ZappValueCard(
            value = review.to,
            label = stringResource(R.string.private_usd_send_review_to),
            gutter = 0.dp,
        )
        BasicText(
            text = stringResource(R.string.private_usd_send_review_test_note),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
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
    state.done?.let { done ->
        ZappSuccessHeader(title = stringRes(state.mode.doneTitle), subtitle = done.body)
        done.note?.let {
            BasicText(
                text = it.getValue(),
                style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
            )
        }
        done.onViewTransaction?.let {
            ZappButton(
                text = stringResource(R.string.private_usd_send_view_tx),
                variant = ZappButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
                onClick = it,
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

// An address is typed or pasted as it is: nothing to suggest, correct or capitalise.
private val ADDRESS_KEYBOARD =
    KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
    )
