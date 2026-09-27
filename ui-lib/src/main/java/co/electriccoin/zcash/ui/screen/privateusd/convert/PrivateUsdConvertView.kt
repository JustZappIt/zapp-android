// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappOfframpHeroAmountField
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegment
import co.electriccoin.zcash.ui.design.component.zapp.ZappSegmentedSelector
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun PrivateUsdConvertView(state: PrivateUsdConvertState) {
    val c = ZappTheme.colors
    val askForNotifications = rememberNotificationsRequest()
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
                PrivateUsdConvertPhase.AMOUNT -> Amount(state)
                PrivateUsdConvertPhase.REVIEW -> Review(state)
            }
            state.message?.let {
                BasicText(
                    text = it.getValue(),
                    style =
                        ZappTheme.typography.caption.copy(
                            color = if (state.isMessageDanger) c.danger else c.textMuted,
                            fontWeight = FontWeight.Medium,
                        ),
                )
            }
        }
        ZappBottomActionBar(
            onBack = state.onBack,
            isBackEnabled = !state.primaryButton.isLoading,
            primaryAction = {
                ZappButton(
                    text = state.primaryButton.text.getValue(),
                    enabled = state.primaryButton.isEnabled,
                    loading = state.primaryButton.isLoading,
                    modifier = Modifier.weight(1f).padding(start = ZappTheme.spacing.lg),
                    onClick = {
                        // Asked before the review, which promises a notification when it's done.
                        if (state.phase == PrivateUsdConvertPhase.AMOUNT) {
                            askForNotifications(state.primaryButton.onClick)
                        } else {
                            state.primaryButton.onClick()
                        }
                    },
                )
            },
        )
    }
}

@Composable
private fun Amount(state: PrivateUsdConvertState) {
    val c = ZappTheme.colors
    Title(stringResource(R.string.convert_title))
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        ZappSectionLabel(text = stringResource(R.string.convert_amount_label))
        ZappSegmentedSelector(
            segments = state.amounts.map { ZappSegment(it.getValue()) },
            selectedIndex = state.selectedAmount ?: -1,
            onSelect = state.onAmountSelect,
        )
        state.customAmount?.let {
            ZappOfframpHeroAmountField(
                symbol = "$",
                state = it,
                secondaryText = state.limits.getValue(),
                isError = state.isMessageDanger,
            )
        }
        state.zecAvailable?.let {
            BasicText(
                text = stringResource(R.string.convert_zec_available) + " " + it.getValue(),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
        }
    }
    when {
        state.quote != null -> {
            ZappSettlementLedger(
                rows =
                    listOfNotNull(
                        ZappSettlementLedgerRow(stringResource(R.string.convert_you_pay), state.quote.pay.getValue()),
                        state.quote.networkFee?.let {
                            ZappSettlementLedgerRow(stringResource(R.string.convert_network_fee), it.getValue())
                        },
                        ZappSettlementLedgerRow(
                            stringResource(R.string.convert_you_receive),
                            state.quote.receive.getValue(),
                        ),
                        ZappSettlementLedgerRow(stringResource(R.string.convert_fees), state.quote.fees.getValue()),
                    ),
                notice =
                    if (state.isQuoting) {
                        stringResource(R.string.convert_quote_loading)
                    } else {
                        state.quote.refreshesIn.getValue()
                    },
            )
        }

        state.isQuoting -> {
            BasicText(
                text = stringResource(R.string.convert_quote_loading),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
        }
    }
}

@Composable
private fun Review(state: PrivateUsdConvertState) {
    val c = ZappTheme.colors
    Title(stringResource(R.string.convert_review_title))
    state.quote?.let { quote ->
        Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs)) {
            BasicText(text = quote.pay.getValue(), style = ZappTheme.typography.display.copy(color = c.text))
            BasicText(
                text = stringResource(R.string.convert_review_arrow),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
            BasicText(
                text = quote.receive.getValue(),
                style = ZappTheme.typography.balanceDisplay.copy(color = c.accent),
            )
        }
        ZappSettlementLedger(
            rows =
                listOfNotNull(
                    quote.networkFee?.let {
                        ZappSettlementLedgerRow(stringResource(R.string.convert_network_fee), it.getValue())
                    },
                    ZappSettlementLedgerRow(stringResource(R.string.convert_fees), quote.fees.getValue()),
                ),
            notice = quote.refreshesIn.getValue(),
            noticeIsDanger = state.isMessageDanger,
        )
    }
    ZappBorderedCard(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.md)) {
        ZappSectionLabel(text = stringResource(R.string.convert_review_how_title))
        BasicText(
            text = stringResource(R.string.convert_review_how_body),
            style = ZappTheme.typography.body.copy(color = c.text),
        )
        BasicText(
            text = stringResource(R.string.convert_review_time, state.duration.getValue()),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
    }
}

@Composable
private fun Title(text: String) {
    BasicText(text = text, style = ZappTheme.typography.display.copy(color = ZappTheme.colors.text))
}

/** Asks for the notification permission where Android needs it, then carries on either way. */
@Composable
private fun rememberNotificationsRequest(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val next = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next.value?.invoke() }
    val current = rememberUpdatedState(launcher)
    return remember(context) {
        { onDone ->
            val granted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) {
                onDone()
            } else {
                next.value = onDone
                current.value.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@PreviewScreens
@Composable
private fun AmountPreview() =
    ZcashTheme {
        PrivateUsdConvertView(
            state =
                PrivateUsdConvertState(
                    phase = PrivateUsdConvertPhase.AMOUNT,
                    amounts = listOf("$1", "$5", "$10", "$20", "Other").map { stringRes(it) },
                    selectedAmount = 1,
                    onAmountSelect = {},
                    customAmount = null,
                    limits = stringRes("Whole dollars, $1 to $20"),
                    zecAvailable = stringRes("2.51 ZEC"),
                    quote =
                        PrivateUsdQuoteState(
                            pay = stringRes("1.0102 ZEC"),
                            networkFee = stringRes("0.00015 ZEC"),
                            receive = stringRes("$4.89"),
                            fees = stringRes("$0.02 relayer + 0.25% Railgun"),
                            refreshesIn = stringRes("Quote refreshes in 4:12"),
                        ),
                    isQuoting = false,
                    message = null,
                    isMessageDanger = false,
                    duration = stringRes("about 6 min"),
                    primaryButton = ButtonState(stringRes("Review")),
                    onBack = {},
                ),
        )
    }

@PreviewScreens
@Composable
private fun CustomPreview() =
    ZcashTheme {
        PrivateUsdConvertView(
            state =
                PrivateUsdConvertState(
                    phase = PrivateUsdConvertPhase.AMOUNT,
                    amounts = listOf("$1", "$5", "$10", "$20", "Other").map { stringRes(it) },
                    selectedAmount = 4,
                    onAmountSelect = {},
                    customAmount = NumberTextFieldState(NumberTextFieldInnerState()) {},
                    limits = stringRes("Whole dollars, $1 to $20"),
                    zecAvailable = stringRes("2.51 ZEC"),
                    quote = null,
                    isQuoting = true,
                    message = null,
                    isMessageDanger = false,
                    duration = stringRes("about 6 min"),
                    primaryButton = ButtonState(stringRes("Review"), isEnabled = false),
                    onBack = {},
                ),
        )
    }
