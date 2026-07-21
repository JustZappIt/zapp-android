package co.electriccoin.zcash.ui.screen.migration.customservertor

import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState

data class MigrationCustomServerTorState(
    val onContinueWithoutTor: () -> Unit,
    val onSwitchServer: () -> Unit,
    override val onBack: () -> Unit,
) : ModalBottomSheetState
