package co.electriccoin.zcash.ui.screen.migration.sending

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

class MigrationSendingVM(
    private val args: MigrationSendingArgs,
    private val sdk: OrchardMigrationSdk,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
) : ViewModel() {

    private val sendLce = mutableLce<Unit>()
    private val failure = MutableStateFlow<TransferResult?>(null)

    val state: StateFlow<LceState<MigrationSendingState>> =
        combine(sendLce.state, failure) { _, f ->
            MigrationSendingState(
                failureSheet = f?.let {
                    MigrationTransferFailureState(
                        message = migrationFailureMessage(it),
                        onRetry = { failure.value = null; send() },
                        onDismiss = { failure.value = null; navigationRouter.back() },
                    )
                }
            )
        }.withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    fun send() = sendLce.execute {
        when (val result = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = args.useTor))) {
            is TransferResult.Success -> navigationRouter.forward(MigrationSuccessArgs(result.txId))
            null -> Unit
            else -> failure.value = result
        }
    }
}
