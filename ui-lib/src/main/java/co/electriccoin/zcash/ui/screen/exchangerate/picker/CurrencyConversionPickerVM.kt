package co.electriccoin.zcash.ui.screen.exchangerate.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.supportedFiatCurrencies
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectFiatCurrencyUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByFiatDisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CurrencyConversionPickerVM(
    private val args: CurrencyConversionPickerArgs,
    private val navigateToSelectFiatCurrency: NavigateToSelectFiatCurrencyUseCase,
) : ViewModel() {
    private var selectedCode = args.selectedCode

    private val mutableState = MutableStateFlow(createState())
    val state: StateFlow<CurrencyConversionPickerState> = mutableState

    private fun createState() =
        CurrencyConversionPickerState(
            items =
                supportedFiatCurrencies.map { currency ->
                    CurrencyConversionPickerItemState(
                        key = currency.code,
                        code = stringRes(currency.code),
                        name = stringResByFiatDisplayName(currency),
                        isSelected = currency.code == selectedCode,
                        onClick = { onCurrencyClick(currency.code) }
                    )
                },
            saveButton =
                ButtonState(
                    text = stringRes(R.string.exchange_rate_currency_picker_save),
                    isEnabled = selectedCode != args.selectedCode,
                    onClick = ::onSaveClick
                ),
            onBack = ::onBack
        )

    private fun onCurrencyClick(code: String) {
        selectedCode = code
        mutableState.update { createState() }
    }

    private fun onSaveClick() =
        viewModelScope.launch {
            val currency = supportedFiatCurrencies.first { it.code == selectedCode }
            navigateToSelectFiatCurrency.onSelected(currency, args)
        }

    private fun onBack() = viewModelScope.launch { navigateToSelectFiatCurrency.onSelectionCancelled(args) }
}
