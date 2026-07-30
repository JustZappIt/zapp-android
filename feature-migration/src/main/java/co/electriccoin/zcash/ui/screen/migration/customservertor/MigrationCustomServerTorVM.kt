package co.electriccoin.zcash.ui.screen.migration.customservertor

import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerArgs
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryArgs
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class MigrationCustomServerTorVM(
    private val args: MigrationCustomServerTorArgs,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {

    val state: StateFlow<MigrationCustomServerTorState?> =
        flowOf(
            MigrationCustomServerTorState(
                body = stringRes(
                    when (args.mode) {
                        MigrationMode.IMMEDIATE ->
                            "The migration transaction can't be broadcast over Tor when you're using a " +
                                "custom server. If you continue, this transaction will be sent without " +
                                "Tor's IP protection."
                        MigrationMode.AUTOMATIC ->
                            "Migration transactions can't be broadcast over Tor when you're using a " +
                                "custom server. If you continue, these transactions will be sent without " +
                                "Tor's IP protection."
                    }
                ),
                riskBody = stringRes(
                    when (args.mode) {
                        MigrationMode.IMMEDIATE ->
                            "Without Tor, your IP address is exposed to the custom server and could be " +
                                "linked to your full balance."
                        MigrationMode.AUTOMATIC ->
                            "Without Tor, your IP address is exposed to the custom server and could be " +
                                "linked to the migration amounts."
                    }
                ),
                onContinueWithoutTor = ::onContinueWithoutTor,
                onSwitchServer = ::onSwitchServer,
                onBack = ::onBack,
            )
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    private fun onContinueWithoutTor() =
        navigationRouter.forward(
            when (args.mode) {
                MigrationMode.IMMEDIATE -> MigrationReviewArgs(mode = args.mode)
                MigrationMode.AUTOMATIC -> MigrationBatteryArgs
            }
        )

    private fun onSwitchServer() = navigationRouter.forward(ChooseServerArgs)

    private fun onBack() = navigationRouter.back()
}
