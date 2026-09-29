// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedger
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettlementLedgerRow
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdScaffold
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertAmountView
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressView

@Composable
internal fun PrivateUsdReverseView(
    state: PrivateUsdReverseState,
    directionSelector: @Composable () -> Unit = {},
) {
    state.progress?.let {
        PrivateUsdProgressView(it)
        return
    }
    PrivateUsdScaffold(
        title = stringResource(R.string.convert_title),
        sendLayout = true,
        info =
            PrivateUsdInfo(
                stringRes(R.string.reverse_title),
                listOf(stringRes(R.string.reverse_intro)),
                listOf(stringRes(R.string.reverse_gas_account), stringRes(R.string.reverse_refund_terms))
            ),
        onBack = state.onBack,
        isBackEnabled = state.isQuoting || !state.primary.isLoading,
        primaryButton = state.primary,
        error = state.error,
    ) {
        when {
            state.showAmount -> {
                PrivateUsdConvertAmountView(
                    amount = state.amount,
                    usdAvailable = state.available,
                    zecAvailable = state.zecAvailable,
                    receiveEstimate = state.zecEstimate,
                    onMax = state.onMax,
                    usdOnTop = true,
                    currencySymbol = state.currencySymbol,
                    note = state.amountNote,
                    isInvalid = state.isAmountInvalid,
                    directionSelector = directionSelector,
                )
            }

            state.showReview -> {
                BasicText(
                    text = stringResource(R.string.convert_review_title),
                    style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
                )
                Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs)) {
                    BasicText(
                        text = state.debit?.getValue().orEmpty(),
                        style = ZappTheme.typography.display.copy(color = ZappTheme.colors.text),
                    )
                    BasicText(
                        text = stringResource(R.string.convert_review_arrow),
                        style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
                    )
                    BasicText(
                        text = stringResource(R.string.reverse_zec, state.receive.orEmpty()),
                        style = ZappTheme.typography.balanceDisplay.copy(color = ZappTheme.colors.accent),
                    )
                }
            }

            else -> {
                BasicText(
                    text = state.status.getValue(),
                    style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
                )
            }
        }
        if (state.escrow != null && !state.showAmount) {
            Ledger(state)
        }
        state.cancel?.let {
            ZappButton(
                text = it.text.getValue(),
                enabled = it.isEnabled,
                variant = ZappButtonVariant.Secondary,
                onClick = it.onClick
            )
        }
        state.rescue?.let {
            ZappButton(
                text = it.text.getValue(),
                enabled = it.isEnabled,
                variant = ZappButtonVariant.Secondary,
                onClick = it.onClick
            )
        }
    }
}

@Composable
private fun Ledger(state: PrivateUsdReverseState) {
    ZappSettlementLedger(
        rows =
            listOfNotNull(
                ZappSettlementLedgerRow(
                    stringResource(R.string.reverse_escrow),
                    checkNotNull(state.escrow).getValue()
                ),
                state.railgunFee?.let {
                    ZappSettlementLedgerRow(
                        stringResource(R.string.reverse_railgun_fee),
                        it.getValue()
                    )
                },
                ZappSettlementLedgerRow(
                    stringResource(R.string.reverse_broadcaster_fee),
                    state.broadcasterFee?.let { it.getValue() }
                        ?: stringResource(R.string.reverse_gas_account)
                ),
                state.debit?.let {
                    ZappSettlementLedgerRow(
                        stringResource(R.string.reverse_total),
                        it.getValue()
                    )
                },
                state.receive?.let {
                    ZappSettlementLedgerRow(
                        stringResource(
                            if (state.receiveIsEstimate) {
                                R.string.reverse_receive_estimate
                            } else {
                                R.string.reverse_receive_net
                            }
                        ),
                        stringResource(R.string.reverse_zec, it)
                    )
                },
            )
    )
}
