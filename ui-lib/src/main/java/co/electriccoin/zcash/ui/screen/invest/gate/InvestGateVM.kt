package co.electriccoin.zcash.ui.screen.invest.gate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestEligibility
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.NavigateToInvestUseCase
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/**
 * I1: where the user lives, asked once and kept on this phone. A prohibited country goes straight to the
 * not-available screen; a restricted one also asks for the qualified-investor attestation, and without it the user
 * sees the same screen. Whatever is confirmed is saved, so Invest is hidden from PAY for a prohibited country.
 */
@Suppress("TooManyFunctions")
internal class InvestGateVM(
    private val settingsRepository: InvestSettingsRepository,
    private val hintProvider: ResidenceHintProvider,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private data class Form(
        val countryCode: String? = null,
        val hintSource: ResidenceHintSource? = null,
        val attested: Boolean = false,
        val qualified: Boolean = false,
        val isPickerOpen: Boolean = false,
        val query: String = "",
        val isSaving: Boolean = false,
        val failed: Boolean = false,
    )

    private val form = MutableStateFlow(Form())

    private val countries: List<Pair<String, String>> by lazy {
        val locale = Locale.getDefault()
        val collator = Collator.getInstance(locale)
        Locale
            .getISOCountries()
            .map { code ->
                code to
                    Locale
                        .Builder()
                        .setRegion(code)
                        .build()
                        .getDisplayCountry(locale)
            }.sortedWith { a, b -> collator.compare(a.second, b.second) }
    }

    init {
        viewModelScope.launch {
            val saved = investCatching { settingsRepository.get() }.getOrNull()?.countryCode
            val initial =
                if (saved != null) {
                    Form(countryCode = saved)
                } else {
                    hintProvider.hint()?.let { Form(countryCode = it.countryCode, hintSource = it.source) } ?: Form()
                }
            form.update { current -> if (current.countryCode == null) initial else current }
        }
    }

    val state: StateFlow<InvestGateState> =
        form
            .map(::buildState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = buildState(form.value),
            )

    private fun buildState(form: Form): InvestGateState {
        val code = form.countryCode
        val name = code?.let(::displayName)
        val eligibility = code?.let(InvestEligibility::of)
        val canContinue =
            when (eligibility) {
                null -> false
                InvestEligibility.PROHIBITED -> true
                else -> form.attested
            }
        return InvestGateState(
            countryName = name,
            suggestion =
                form.hintSource?.let { source ->
                    when (source) {
                        ResidenceHintSource.SIM -> stringRes(R.string.invest_gate_suggested_sim, name.orEmpty())
                        ResidenceHintSource.LOCALE -> stringRes(R.string.invest_gate_suggested_locale, name.orEmpty())
                    }
                },
            attestation =
                name?.takeIf { eligibility != InvestEligibility.PROHIBITED }?.let {
                    InvestCheckboxState(stringRes(R.string.invest_gate_attest, it), form.attested, ::onToggleAttested)
                },
            qualifiedInvestor =
                if (eligibility == InvestEligibility.RESTRICTED) {
                    InvestCheckboxState(stringRes(R.string.invest_gate_qualified), form.qualified, ::onToggleQualified)
                } else {
                    null
                },
            primaryButton =
                ButtonState(
                    text = stringRes(R.string.invest_gate_continue),
                    isEnabled = canContinue && !form.isSaving,
                    onClick = ::onContinue,
                ),
            isSaving = form.isSaving,
            errorText = if (form.failed) stringRes(R.string.invest_error_generic) else null,
            onChangeCountry = ::onOpenPicker,
            picker = if (form.isPickerOpen) pickerState(form) else null,
            onBack = ::onBack,
        )
    }

    private fun pickerState(form: Form): CountryPickerState {
        val query = form.query.trim()
        val items =
            countries
                .filter { (code, name) ->
                    query.isEmpty() || name.contains(query, ignoreCase = true) || code.equals(query, ignoreCase = true)
                }.map { (code, name) ->
                    CountryItemState(
                        code = code,
                        name = name,
                        isSelected = code == form.countryCode,
                        onClick = { onPickCountry(code) },
                    )
                }
        return CountryPickerState(
            query = form.query,
            items = items,
            onQueryChange = { next -> this.form.update { it.copy(query = next) } },
            onBack = ::onClosePicker,
        )
    }

    private fun displayName(code: String): String =
        countries.firstOrNull { it.first == code }?.second
            ?: Locale
                .Builder()
                .setRegion(code)
                .build()
                .getDisplayCountry(Locale.getDefault())

    private fun onToggleAttested() = form.update { it.copy(attested = !it.attested) }

    private fun onToggleQualified() = form.update { it.copy(qualified = !it.qualified) }

    private fun onOpenPicker() = form.update { it.copy(isPickerOpen = true, query = "") }

    private fun onClosePicker() = form.update { it.copy(isPickerOpen = false) }

    // A new country resets both attestations: they name the country, so an earlier tick doesn't carry over.
    private fun onPickCountry(code: String) =
        form.update {
            if (code == it.countryCode) {
                it.copy(isPickerOpen = false)
            } else {
                Form(countryCode = code)
            }
        }

    private fun onBack() {
        if (form.value.isPickerOpen) onClosePicker() else navigationRouter.back()
    }

    private fun onContinue() {
        val current = form.value
        val code = current.countryCode ?: return
        if (current.isSaving) return
        val eligibility = InvestEligibility.of(code)
        val qualified = eligibility == InvestEligibility.RESTRICTED && current.qualified
        form.update { it.copy(isSaving = true, failed = false) }
        viewModelScope.launch {
            investCatching {
                settingsRepository.setResidence(code, qualifiedInvestor = qualified)
                settingsRepository.get()
            }.onSuccess { saved ->
                form.update { it.copy(isSaving = false) }
                navigationRouter.replace(NavigateToInvestUseCase.routeFor(saved).let(::unavailableFor))
            }.onFailure { e ->
                Twig.warn(e) { "InvestGateVM: saving residence failed" }
                form.update { it.copy(isSaving = false, failed = true) }
            }
        }
    }

    // After the gate, a country that still isn't available (prohibited, or restricted without the attestation)
    // shows the not-available screen rather than the gate again.
    private fun unavailableFor(route: Any): Any {
        val code = form.value.countryCode
        return if (route == InvestGateArgs && code != null) InvestUnavailableArgs(code) else route
    }
}
