package co.electriccoin.zcash.ui.screen.exchangerate.picker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSelectionRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue

@Composable
internal fun CurrencyConversionPickerView(state: CurrencyConversionPickerState?) {
    if (state == null) {
        CircularScreenProgressIndicator()
        return
    }

    val c = ZappTheme.colors
    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        containerColor = c.bg,
        topBar = {
            ZappScreenHeader(
                title = stringResource(R.string.exchange_rate_currency_picker_title),
            )
        },
        bottomBar = {
            ZappBottomActionBar(
                onBack = state.onBack,
                primaryAction = {
                    ZappButton(
                        text = state.saveButton.text.getValue(),
                        onClick = state.saveButton.onClick,
                        enabled = state.saveButton.isEnabled,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                    )
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberLazyListState(),
            contentPadding =
                PaddingValues(
                    start = 14.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    end = 14.dp,
                    bottom = padding.calculateBottomPadding() + 12.dp
                ),
        ) {
            item(
                key = "currencies",
                contentType = "currencies"
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(c.surface, RectangleShape)
                            .border(BorderStroke(1.dp, c.border), RectangleShape),
                ) {
                    state.items.forEachIndexed { index, item ->
                        CurrencyItem(item)
                        if (index != state.items.lastIndex) {
                            ZappRowDivider(inset = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrencyItem(item: CurrencyConversionPickerItemState) {
    ZappSelectionRow(
        title = item.code.getValue(),
        subtitle = item.name.getValue(),
        isSelected = item.isSelected,
        onClick = item.onClick,
    )
}

@PreviewScreens
@Composable
private fun CurrencyConversionPickerPreview() =
    ZcashTheme {
        ProvideZappTheme {
            CurrencyConversionPickerView(state = CurrencyConversionPickerState.preview)
        }
    }
