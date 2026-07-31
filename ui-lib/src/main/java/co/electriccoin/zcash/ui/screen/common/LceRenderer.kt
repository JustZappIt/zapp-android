package co.electriccoin.zcash.ui.screen.common

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.electriccoin.zcash.ui.common.model.LceError
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationBottomSheet

@Composable
fun <T : Any> LceRenderer(
    state: LceState<T>,
    modifier: Modifier = Modifier,
    loading: @Composable (Boolean) -> Unit = { },
    error: @Composable (LceError) -> Unit = ::LceErrorRenderer,
    content: @Composable (T) -> Unit,
) {
    Box(modifier) {
        state.content?.let { content(it) }
        loading(state.isLoading)
        state.error?.let { error(it) }
    }
}

@Composable
fun LceErrorRenderer(state: LceError) {
    when (state) {
        is LceError.BottomSheet -> ZashiConfirmationBottomSheet(state.state)
    }
}
