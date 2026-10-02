package co.electriccoin.zcash.ui.screen.invest.gate

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestGateState(
    /** Why we ask (first time), or that the answer stays on the phone (a change from Settings). */
    val body: StringResource,
    /** The chosen country's name in the phone's language; null until one is suggested or picked. */
    val countryName: String?,
    /** "We suggested Indonesia from your SIM…", shown while the suggestion stands. */
    val suggestion: StringResource?,
    /** Absent until a country is chosen, and for a prohibited one (the sentence would contradict itself). */
    val attestation: InvestCheckboxState?,
    /** Only for countries where Ondo stocks are limited to qualified or professional investors. */
    val qualifiedInvestor: InvestCheckboxState?,
    val primaryButton: ButtonState,
    val isSaving: Boolean,
    val errorText: StringResource?,
    val onChangeCountry: () -> Unit,
    /** Non-null while the country list is open over the gate. */
    val picker: CountryPickerState?,
    /** "Buying stops" before a country change that leaves the user unable to buy; Settings only. */
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
