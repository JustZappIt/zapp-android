// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import android.content.ClipboardManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappCompactButton
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

@Composable
internal fun PrivateUsdSendView(state: PrivateUsdSendState) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ZappTheme.spacing.xl, vertical = ZappTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xl2),
        ) {
            when (state.phase) {
                PrivateUsdSendPhase.FORM -> Form(state)
                PrivateUsdSendPhase.REVIEW -> Review(state)
                PrivateUsdSendPhase.SENDING -> Sending(state)
                PrivateUsdSendPhase.DONE -> Done(state)
            }
            state.error?.let {
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.caption.copy(color = c.danger, fontWeight = FontWeight.Medium),
                )
            }
        }
        ZappBottomActionBar(
            onBack = state.onBack,
            isBackEnabled = state.phase != PrivateUsdSendPhase.SENDING,
            primaryAction = {
                ZappButton(
                    text = state.primaryButton.text.getValue(),
                    enabled = state.primaryButton.isEnabled,
                    loading = state.primaryButton.isLoading,
                    modifier = Modifier.weight(1f).padding(start = ZappTheme.spacing.lg),
                    onClick = state.primaryButton.onClick,
                )
            },
        )
    }
}

@Composable
private fun Form(state: PrivateUsdSendState) {
    Title(if (state.isWithdrawal) R.string.private_usd_withdraw_title else R.string.private_usd_send_title)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZappSectionLabel(text = stringResource(R.string.private_usd_send_amount), modifier = Modifier.weight(1f))
            ZappCompactButton(text = stringResource(R.string.private_usd_send_max), onClick = state.onMax)
        }
        ZappOfframpHeroAmountField(
            symbol = state.amountSymbol,
            state = state.amount,
            secondaryText = state.amountError?.getValue(),
            isError = state.amountError != null,
            balance =
                state.available?.let {
                    ZappFieldBalance(stringResource(R.string.private_usd_send_available), it.getValue())
                },
        )
    }
    Recipient(state)
    if (state.isWithdrawal) WithdrawWarning()
}

@Composable
private fun Recipient(state: PrivateUsdSendState) {
    val context = LocalContext.current
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
            trailingIcon = {
                ZappCompactButton(
                    text = stringResource(R.string.private_usd_send_paste),
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard
                            ?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            ?.trim()
                            ?.let(state.onRecipientChange)
                    },
                )
            },
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
private fun WithdrawWarning() {
    val c = ZappTheme.colors
    ZappBorderedCard(borderColor = c.accent, verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.sm)) {
        ZappSectionLabel(text = stringResource(R.string.private_usd_send_withdraw_warning_title), color = c.accentText)
        BasicText(
            text = stringResource(R.string.private_usd_send_withdraw_warning_body),
            style = ZappTheme.typography.body.copy(color = c.text),
        )
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
    if (state.isWithdrawal) WithdrawWarning()
}

@Composable
private fun Sending(state: PrivateUsdSendState) {
    val c = ZappTheme.colors
    Title(R.string.private_usd_send_proving)
    BasicText(
        text = stringResource(R.string.private_usd_send_proving_detail),
        style = ZappTheme.typography.body.copy(color = c.textMuted),
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
    BasicText(text = stringResource(text), style = ZappTheme.typography.display.copy(color = ZappTheme.colors.text))
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
                    assets = listOf("tUSD", "WETH"),
                    selectedAsset = 0,
                    onAssetSelect = {},
                    amount = NumberTextFieldState(NumberTextFieldInnerState()) {},
                    amountSymbol = "$",
                    available = stringRes("$12.34"),
                    onMax = {},
                    recipient = "",
                    onRecipientChange = {},
                    recipientError = null,
                    amountError = null,
                    review = null,
                    proofProgress = null,
                    done = null,
                    error = null,
                    primaryButton = ButtonState(stringRes("Review"), isEnabled = false),
                    onBack = {},
                ),
        )
    }
