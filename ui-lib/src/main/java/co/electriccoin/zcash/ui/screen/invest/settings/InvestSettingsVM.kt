package co.electriccoin.zcash.ui.screen.invest.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettings
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.demo.InvestDemoControlsArgs
import co.electriccoin.zcash.ui.screen.invest.gate.InvestChangeCountryArgs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class InvestSettingsState(
    /** The saved country of residence, or "Not set". */
    val country: StringResource,
    /** What that country means for Invest: buy and sell, sell only, or nothing until a country is chosen. */
    val status: StringResource,
    val onCountryClick: () -> Unit,
    /** Demo builds only: how the demo engine's trades end. */
    val onDemoControlsClick: (() -> Unit)? = null,
)

/**
 * S1: what Invest keeps on this phone, starting with the country of residence. Changing it opens the gate in its
 * change mode, which confirms before a country where buying stops.
 */
internal class InvestSettingsVM(
    settingsRepository: InvestSettingsRepository,
    private val navigationRouter: NavigationRouter,
    private val isDemo: Boolean = false,
) : ViewModel() {
    /** Null until the saved settings are read. */
    val state: StateFlow<InvestSettingsState?> =
        settingsRepository.settings
            .map(::buildState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

    private fun buildState(settings: InvestSettings): InvestSettingsState {
        val name = settings.countryCode?.let(InvestFormat::countryName)
        return InvestSettingsState(
            country = name?.let(::stringRes) ?: stringRes(R.string.invest_settings_country_none),
            status =
                when {
                    name == null -> stringRes(R.string.invest_settings_status_none)
                    settings.isAvailable -> stringRes(R.string.invest_settings_status_available)
                    else -> stringRes(R.string.invest_sell_only_banner, name)
                },
            onCountryClick = { navigationRouter.forward(InvestChangeCountryArgs) },
            onDemoControlsClick = { navigationRouter.forward(InvestDemoControlsArgs) }.takeIf { isDemo },
        )
    }

    fun onBack() = navigationRouter.back()
}
