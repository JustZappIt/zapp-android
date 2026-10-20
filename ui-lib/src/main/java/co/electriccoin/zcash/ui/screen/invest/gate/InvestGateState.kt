package co.electriccoin.zcash.ui.screen.invest.gate

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestGateState(
    val body: StringResource,
    /** Null until a country is suggested or picked. */
    val countryName: String?,
    val suggestion: StringResource?,
    /** Absent until a country is chosen, and for a prohibited one. */
    val attestation: InvestCheckboxState?,
    /** Only where Ondo stocks are limited to qualified or professional investors. */
    val qualifiedInvestor: InvestCheckboxState?,
    val primaryButton: ButtonState,
    val errorText: StringResource?,
    val onChangeCountry: () -> Unit,
    /** Non-null while the country list is open. */
    val picker: CountryPickerState?,
    /** Settings only: confirms a change to a country where buying stops. */
    val confirmation: ZappConfirmationState? = null,
    val onBack: () -> Unit,
)

internal data class InvestCheckboxState(
    val text: StringResource,
    val isChecked: Boolean,
    val onClick: () -> Unit,
)

internal data class CountryPickerState(
    val query: String,
    val items: List<CountryItemState>,
    val onQueryChange: (String) -> Unit,
    val onBack: () -> Unit,
)

internal data class CountryItemState(
    val code: String,
    val name: String,
    val isSelected: Boolean,
    val onClick: () -> Unit,
)
