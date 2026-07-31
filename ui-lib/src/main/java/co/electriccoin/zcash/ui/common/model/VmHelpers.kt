package co.electriccoin.zcash.ui.common.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

fun <T> Flow<T>.stateIn(
    viewModel: ViewModel,
    initialValue: T,
    started: SharingStarted = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
): StateFlow<T> = stateIn(viewModel.viewModelScope, started, initialValue)

fun <T> Flow<T>.stateIn(
    viewModel: ViewModel,
    started: SharingStarted = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
): StateFlow<T?> = stateIn(viewModel.viewModelScope, started, null)

fun <T> Flow<T>.stateIn(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
): StateFlow<T?> = stateIn(scope, started, null)

fun <T> Flow<T?>.withLce(
    source: LceSource,
    errorMapper: ((LceContent.Error) -> LceError)? = null,
): Flow<LceState<T>> =
    combine(this, source.loading, source.error) { content, loading, error ->
        LceState(
            content = content,
            isLoading = loading,
            error = error?.let { errorMapper?.invoke(it) },
        )
    }

fun <T> Flow<LceState<T>>.stateIn(viewModel: ViewModel): StateFlow<LceState<T>> =
    stateIn(viewModel, LceState(content = null))
