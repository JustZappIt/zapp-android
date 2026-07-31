package co.electriccoin.zcash.ui.screen.balances.breakdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappModalBottomSheetDragHandle
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.orHiddenString
import co.electriccoin.zcash.ui.design.util.stringRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BalanceBreakdownView(
    state: BalanceBreakdownState?,
    sheetState: SheetState = rememberScreenModalBottomSheetState(),
) {
    ZashiScreenModalBottomSheet(
        state = state,
        sheetState = sheetState,
        containerColor = ZappTheme.colors.surface,
        dragHandle = { ZappModalBottomSheetDragHandle() },
        content = { sheetContentState, contentPadding ->
            BottomSheetContent(sheetContentState, contentPadding, modifier = Modifier.weight(1f, false))
        },
    )
}

@Composable
private fun BottomSheetContent(
    state: BalanceBreakdownState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .background(c.surface)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = contentPadding.calculateBottomPadding()
                ),
    ) {
        BasicText(
            modifier = Modifier.fillMaxWidth(),
            text = state.title.getValue(),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.Black),
        )
        Spacer(8.dp)
        BasicText(
            modifier = Modifier.fillMaxWidth(),
            text = state.subtitle.getValue(),
            style = ZappTheme.typography.body.copy(color = c.textMuted),
        )
        Spacer(24.dp)

        BalanceCard(state.total, isTotal = true, modifier = Modifier.fillMaxWidth())

        Spacer(8.dp)
        state.pools.chunked(2).forEachIndexed { index, row ->
            if (index != 0) {
                Spacer(8.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { pool ->
                    BalanceCard(pool, modifier = Modifier.weight(1f))
                }
                // Keep single-item rows left-aligned with a matching empty cell.
                if (row.size == 1) {
                    Spacer(1f)
                }
            }
        }

        Spacer(32.dp)
        ZappButton(
            text = state.positive.text.getValue(),
            modifier = Modifier.fillMaxWidth(),
            enabled = state.positive.isEnabled && !state.positive.isLoading,
            onClick = { state.positive.onClick() },
        )
    }
}

@Composable
private fun BalanceCard(
    state: BalanceBreakdownItemState,
    modifier: Modifier = Modifier,
    isTotal: Boolean = false,
) {
    val c = ZappTheme.colors
    ZappBorderedCard(
        modifier = modifier,
        borderColor = if (isTotal) c.text else c.border,
        padding = 16.dp,
    ) {
        BasicText(
            text = state.title.getValue(),
            style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
        )
        Spacer(6.dp)
        BasicText(
            text =
                stringRes(state.amount) orHiddenString
                    stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder),
            style =
                ZappTheme.typography.rowTitle.copy(
                    color = c.text,
                    fontWeight = FontWeight.Black,
                ),
        )
        state.fiat?.let { fiat ->
            Spacer(2.dp)
            BasicText(
                text =
                    fiat orHiddenString
                        stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder),
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textSubtle),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreens
@Composable
private fun BalanceBreakdownPreview() =
    ZcashTheme {
        ProvideZappTheme {
            BalanceBreakdownView(
                state =
                    BalanceBreakdownState(
                        title = stringRes("Total Balance Across Pools"),
                        subtitle = stringRes("Your ZEC balance is now broken down by Zcash pool."),
                        total =
                            BalanceBreakdownItemState(
                                title = stringRes("Total Balance"),
                                amount = Zatoshi(5404772),
                                fiat = stringRes("$133.21"),
                            ),
                        pools =
                            listOf(
                                BalanceBreakdownItemState(stringRes("Ironwood"), Zatoshi(0), null),
                                BalanceBreakdownItemState(stringRes("Orchard"), Zatoshi(5404772), null),
                                BalanceBreakdownItemState(stringRes("Sapling"), Zatoshi(0), null),
                                BalanceBreakdownItemState(stringRes("Transparent"), Zatoshi(0), null),
                            ),
                        positive = ButtonState(text = stringRes("Got it")),
                        onBack = {},
                    ),
            )
        }
    }
