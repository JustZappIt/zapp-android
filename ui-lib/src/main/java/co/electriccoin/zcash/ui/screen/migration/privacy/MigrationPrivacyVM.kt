package co.electriccoin.zcash.ui.screen.migration.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MigrationPrivacyVM(
    private val args: MigrationPrivacyArgs,
    private val navigationRouter: NavigationRouter,
    private val isTorEnabled: IsTorEnabledUseCase,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
) : ViewModel() {

    val state: StateFlow<MigrationPrivacyState?> =
        isTorEnabled.observe()
            .map { tor ->
                MigrationPrivacyState(
                    checkbox = CheckboxState(
                        title = stringRes("Enable Tor Protection"),
                        subtitle = stringRes(
                            "Routes your connection through the Tor network for enhanced anonymity and " +
                                "privacy protection."
                        ),
                        isChecked = tor,
                        onClick = { onTorToggle(!tor) },
                    ),
                    onConfirm = ::onConfirm,
                    onBack = ::onBack,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    private fun onTorToggle(enabled: Boolean) {
        viewModelScope.launch { isTorEnabledStorageProvider.store(enabled) }
    }

    private fun onConfirm() =
        navigationRouter.forward(
            MigrationReviewArgs(mode = args.mode, backgroundAvailable = args.backgroundAvailable)
        )

    private fun onBack() = navigationRouter.back()
}
