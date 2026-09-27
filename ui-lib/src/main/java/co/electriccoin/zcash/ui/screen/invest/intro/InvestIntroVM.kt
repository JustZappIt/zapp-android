package co.electriccoin.zcash.ui.screen.invest.intro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupReturnRoute
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.home.backup.WalletBackupDetail
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.common.toInvestMessage
import co.electriccoin.zcash.ui.screen.invest.home.InvestHomeArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * I2: three facts, then "Set up Invest", which derives the private account's key and signs in (the repository's
 * first holdings refresh does both). The private account comes from the recovery phrase, so the phrase has to be
 * backed up first; until it is, the button opens the existing backup flow instead, which then comes back here and
 * setup carries on by itself.
 */
internal class InvestIntroVM(
    private val investRepository: InvestRepository,
    private val settingsRepository: InvestSettingsRepository,
    private val walletBackupFlag: WalletBackupFlagStorageProvider,
    private val walletBackupReturnRoute: WalletBackupReturnRoute,
    isTorEnabled: IsTorEnabledUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private data class Setup(
        val isRunning: Boolean = false,
        val error: StringResource? = null,
    )

    private val setup = MutableStateFlow(Setup())

    // Set when the user went to back up from here: once the phrase is saved, setup continues without a second tap.
    private var isAwaitingBackup = false

    init {
        viewModelScope.launch {
            walletBackupFlag.observe().collect { backedUp ->
                if (backedUp && isAwaitingBackup) {
                    isAwaitingBackup = false
                    onSetUp()
                }
            }
        }
    }

    val state: StateFlow<InvestIntroState?> =
        combine(walletBackupFlag.observe(), isTorEnabled.observe(), setup) { backedUp, torOn, current ->
            InvestIntroState(
                primaryButton =
                    ButtonState(
                        text =
                            stringRes(
                                if (backedUp) R.string.invest_intro_setup else R.string.invest_intro_backup_first,
                            ),
                        isEnabled = !current.isRunning,
                        onClick = { onPrimaryClick(backedUp) },
                    ),
                isSettingUp = current.isRunning,
                hint = stringRes(if (torOn) R.string.invest_intro_setup_hint_tor else R.string.invest_intro_setup_hint),
                errorText = current.error,
                onBack = ::onBack,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    private fun onPrimaryClick(backedUp: Boolean) {
        if (backedUp) {
            onSetUp()
        } else {
            isAwaitingBackup = true
            walletBackupReturnRoute.route = InvestIntroArgs::class
            navigationRouter.forward(WalletBackupDetail(isOpenedFromSeedBackupInfo = false))
        }
    }

    override fun onCleared() {
        if (walletBackupReturnRoute.route == InvestIntroArgs::class) walletBackupReturnRoute.route = null
        super.onCleared()
    }

    private fun onSetUp() {
        if (setup.value.isRunning) return
        setup.update { Setup(isRunning = true) }
        viewModelScope.launch {
            investCatching {
                investRepository.refreshHoldings()
                settingsRepository.completeSetup()
            }.onSuccess {
                setup.update { Setup() }
                navigationRouter.replace(InvestHomeArgs)
            }.onFailure { e ->
                Twig.warn(e) { "InvestIntroVM: setup failed" }
                setup.update { Setup(error = e.toInvestMessage()) }
            }
        }
    }

    private fun onBack() = navigationRouter.back()
}
