package co.electriccoin.zcash.ui.screen.invest.gate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestEligibility
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.NavigateToInvestUseCase
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
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
 * Where the user lives, kept on this phone. With [isChange] it is Settings › Invest's country change, which
 * confirms before saving a country where buying stops.
 */
@Suppress("TooManyFunctions")
internal class InvestGateVM(
    private val settingsRepository: InvestSettingsRepository,
    private val hintProvider: ResidenceHintProvider,
    private val navigationRouter: NavigationRouter,
    private val isChange: Boolean = false,
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
        val isConfirming: Boolean = false,
    )

    private val form = MutableStateFlow(Form())

    private val countries: List<Pair<String, String>> by lazy {
        val collator = Collator.getInstance(Locale.getDefault())
        Locale
            .getISOCountries()
            .map { code -> code to InvestFormat.countryName(code) }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
    }

    init {
        viewModelScope.launch {
            val saved = investCatching { settingsRepository.get() }.getOrNull()
            val savedCountry = saved?.countryCode
            val initial =
                if (savedCountry != null) {
                    // Kept, or reopening their own country would quietly turn a qualified investor sell-only.
                    Form(countryCode = savedCountry, qualified = saved.qualifiedInvestor)
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
        val name = code?.let(InvestFormat::countryName)
        val eligibility = code?.let(InvestEligibility::of)
        return InvestGateState(
            body = stringRes(if (isChange) R.string.invest_change_body else R.string.invest_gate_body),
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
                    text = stringRes(if (isChange) R.string.invest_change_save else R.string.invest_gate_continue),
                    isEnabled = canContinue(eligibility, form) && !form.isSaving,
                    isLoading = form.isSaving,
                    onClick = ::onContinue,
                ),
            errorText = if (form.failed) stringRes(R.string.invest_error_generic) else null,
            onChangeCountry = ::onOpenPicker,
            picker = if (form.isPickerOpen) pickerState(form) else null,
            confirmation = if (form.isConfirming && code != null) confirmation(code, name.orEmpty()) else null,
            onBack = ::onBack,
        )
    }

    private fun canContinue(
        eligibility: InvestEligibility?,
        form: Form,
    ) = when (eligibility) {
        null -> false
        InvestEligibility.PROHIBITED -> true
        else -> form.attested
    }

    private fun confirmation(
        code: String,
        name: String,
    ) = ZappConfirmationState(
        title = stringRes(R.string.invest_change_confirm_title),
        message =
            if (InvestEligibility.of(code) == InvestEligibility.PROHIBITED) {
                stringRes(R.string.invest_change_confirm_body_prohibited, name)
            } else {
                stringRes(R.string.invest_change_confirm_body_restricted, name)
            },
        primaryButton = ButtonState(stringRes(R.string.invest_change_confirm_save), onClick = ::onConfirmSave),
        secondaryButton = ButtonState(stringRes(R.string.invest_change_confirm_cancel), onClick = ::onCancelConfirm),
        onBack = ::onCancelConfirm,
    )

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

    private fun onToggleAttested() = form.update { it.copy(attested = !it.attested) }

    private fun onToggleQualified() = form.update { it.copy(qualified = !it.qualified) }

    private fun onOpenPicker() = form.update { it.copy(isPickerOpen = true, query = "") }

    private fun onClosePicker() = form.update { it.copy(isPickerOpen = false) }

    // The attestations name the country, so a new one clears them.
    private fun onPickCountry(code: String) =
        form.update {
            if (code == it.countryCode) {
                it.copy(isPickerOpen = false)
            } else {
                Form(countryCode = code)
            }
        }

    private fun onBack() {
        when {
            form.value.isConfirming -> onCancelConfirm()
            form.value.isPickerOpen -> onClosePicker()
            else -> navigationRouter.back()
        }
    }

    private fun onCancelConfirm() = form.update { it.copy(isConfirming = false) }

    private fun onConfirmSave() {
        form.update { it.copy(isConfirming = false) }
        save()
    }

    private fun onContinue() {
        val current = form.value
        val code = current.countryCode ?: return
        if (current.isSaving) return
        val next = InvestSettings(countryCode = code, qualifiedInvestor = qualifiedFor(code, current))
        if (isChange && !next.isAvailable) {
            form.update { it.copy(isConfirming = true) }
        } else {
            save()
        }
    }

    private fun qualifiedFor(
        code: String,
        current: Form,
    ) = InvestEligibility.of(code) == InvestEligibility.RESTRICTED && current.qualified

    private fun save() {
        val current = form.value
        val code = current.countryCode ?: return
        if (current.isSaving) return
        val qualified = qualifiedFor(code, current)
        form.update { it.copy(isSaving = true, failed = false) }
        viewModelScope.launch {
            investCatching {
                settingsRepository.setResidence(code, qualifiedInvestor = qualified)
                settingsRepository.get()
            }.onSuccess { saved ->
                form.update { it.copy(isSaving = false) }
                if (isChange) {
                    navigationRouter.back()
                } else {
                    navigationRouter.replace(NavigateToInvestUseCase.routeFor(saved).let(::unavailableFor))
                }
            }.onFailure { e ->
                Twig.warn(e) { "InvestGateVM: saving residence failed" }
                form.update { it.copy(isSaving = false, failed = true) }
            }
        }
    }

    // A country that still isn't available shows the not-available screen, not the gate again.
    private fun unavailableFor(route: Any): Any {
        val code = form.value.countryCode
        return if (route == InvestGateArgs && code != null) InvestUnavailableArgs(code) else route
    }
}
