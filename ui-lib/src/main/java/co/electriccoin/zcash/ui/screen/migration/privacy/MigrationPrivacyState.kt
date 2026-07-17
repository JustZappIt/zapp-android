package co.electriccoin.zcash.ui.screen.migration.privacy

import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState

data class MigrationPrivacyState(
    val checkbox: CheckboxState,
    val onConfirm: () -> Unit,
    override val onBack: () -> Unit,
) : ModalBottomSheetState
