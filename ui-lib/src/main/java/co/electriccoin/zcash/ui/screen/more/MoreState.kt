package co.electriccoin.zcash.ui.screen.more

import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.util.StringResource
import kotlinx.collections.immutable.ImmutableList

data class MoreState(
    val version: StringResource,
    val onBack: () -> Unit,
    val onVersionLongClick: () -> Unit,
    val onVersionDoubleClick: () -> Unit,
    val items: ImmutableList<ListItemState>,
)
