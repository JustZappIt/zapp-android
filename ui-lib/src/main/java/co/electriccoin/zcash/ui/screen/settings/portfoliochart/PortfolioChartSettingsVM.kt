package co.electriccoin.zcash.ui.screen.settings.portfoliochart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.IsPortfolioChartEnabledProvider
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PortfolioChartSettingsVM(
    private val navigationRouter: NavigationRouter,
    private val isPortfolioChartEnabledProvider: IsPortfolioChartEnabledProvider,
) : ViewModel() {
    private val selectedIsEnabled = MutableStateFlow<Boolean?>(null)
    private val isSaveInProgress = MutableStateFlow(false)

    val state =
        combine(
            isPortfolioChartEnabledProvider.observe(),
            selectedIsEnabled,
            isSaveInProgress,
        ) { savedIsEnabled, selectedIsEnabled, isSaveInProgress ->
            createState(
                savedIsEnabled = savedIsEnabled,
                selectedIsEnabled = selectedIsEnabled ?: savedIsEnabled,
                isSaveInProgress = isSaveInProgress,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    savedIsEnabled = true,
                    selectedIsEnabled = true,
                    isSaveInProgress = false,
                ),
        )

    private fun createState(
        savedIsEnabled: Boolean,
        selectedIsEnabled: Boolean,
        isSaveInProgress: Boolean,
    ) =
        PortfolioChartSettingsState(
            isEnabled = selectedIsEnabled,
            saveButton =
                ButtonState(
                    text = stringRes(R.string.settings_portfolio_chart_save),
                    isEnabled = selectedIsEnabled != savedIsEnabled && !isSaveInProgress,
                    isLoading = isSaveInProgress,
                    onClick = ::onSave,
                ),
            onEnabledChange = ::onEnabledChange,
            onBack = ::onBack,
        )

    private fun onEnabledChange(isEnabled: Boolean) {
        if (!isSaveInProgress.value) {
            selectedIsEnabled.update { isEnabled }
        }
    }

    private fun onSave() {
        val selection = selectedIsEnabled.value ?: return
        if (!isSaveInProgress.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                isPortfolioChartEnabledProvider.store(selection)
                selectedIsEnabled.update { null }
                navigationRouter.back()
            } finally {
                isSaveInProgress.update { false }
            }
        }
    }

    private fun onBack() {
        if (!isSaveInProgress.value) {
            navigationRouter.back()
        }
    }
}
