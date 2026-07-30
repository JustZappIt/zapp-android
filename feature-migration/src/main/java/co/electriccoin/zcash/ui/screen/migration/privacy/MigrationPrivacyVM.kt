package co.electriccoin.zcash.ui.screen.migration.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryArgs
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MigrationPrivacyVM(
    private val args: MigrationPrivacyArgs,
    private val navigationRouter: NavigationRouter,
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider,
) : ViewModel() {
    // Always shown with Tor defaulted ON, independent of the global setting's actual current
    // value — the sheet is only ever reached when the global setting is off, but the migration's
    // own choice still starts from the privacy-preferred default rather than mirroring that off
    // state.
    private val useTor = MutableStateFlow(true)

    val state: StateFlow<MigrationPrivacyState?> =
        useTor
            .map { tor ->
                MigrationPrivacyState(
                    body =
                        stringRes(
                            when (args.mode) {
                                MigrationMode.IMMEDIATE -> {
                                    "If Tor is available in your region, we strongly recommend enabling it to " +
                                        "prevent linking your balance to your IP address. You can also use a " +
                                        "trusted VPN if Tor is unavailable in your region."
                                }

                                MigrationMode.AUTOMATIC -> {
                                    "If Tor is available in your region, we strongly recommend enabling it to " +
                                        "prevent linking the migration amounts to your IP address. You can also " +
                                        "use a trusted VPN if Tor is unavailable in your region."
                                }
                            }
                        ),
                    checkbox =
                        CheckboxState(
                            title = stringRes("Enable Tor Protection"),
                            subtitle =
                                stringRes(
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

    // Only updates local VM state — this is a migration-scoped choice, not a global app setting,
    // and it must not be persisted until the user actually taps Confirm (previously this stored
    // to the global Tor setting as a side effect of every toggle, before the user had committed to
    // a choice).
    private fun onTorToggle(enabled: Boolean) {
        useTor.value = enabled
    }

    private fun onConfirm() {
        val chosenUseTor = useTor.value
        viewModelScope.launch { isMigrationTorEnabledStorageProvider.store(chosenUseTor) }
        navigationRouter.forward(
            when (args.mode) {
                MigrationMode.IMMEDIATE -> MigrationReviewArgs(mode = args.mode)
                MigrationMode.AUTOMATIC -> MigrationBatteryArgs
            }
        )
    }

    private fun onBack() = navigationRouter.back()
}
