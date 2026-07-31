package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.ui.design.component.ZashiConfirmationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

data class LceState<out T>(
    val content: T?,
    val isLoading: Boolean = content == null,
    val error: LceError? = null,
)

sealed interface LceError {
    data class BottomSheet(
        val state: ZashiConfirmationState
    ) : LceError
}

interface LceSource {
    val loading: Flow<Boolean>
    val error: Flow<LceContent.Error?>
}

fun groupLce(vararg lces: MutableLce<*>) =
    object : LceSource {
        override val loading: Flow<Boolean> =
            combine(lces.map { it.state }) { states ->
                states.any { it.loading }
            }

        override val error: Flow<LceContent.Error?> =
            lces.map { lce -> lce.state.map { it.error } }.merge()
    }
