package co.electriccoin.zcash.ui.common.model.migration

import cash.z.ecc.android.sdk.TransferResult

data class MigrationTransferFailureState(
    val message: String,
    // Null when the failure isn't safely resubmittable (e.g. a non-retryable SubmitResult) — the
    // shared bottom sheet omits the Retry button entirely in that case rather than silently
    // wiring it to mean "go back".
    val onRetry: (() -> Unit)?,
    val onDismiss: () -> Unit,
)

fun migrationFailureMessage(result: TransferResult): String =
    when (result) {
        is TransferResult.NetworkError -> "Couldn't reach the network. Check your connection and try again."
        TransferResult.InvalidNote -> "This transfer's note was already spent elsewhere. Reschedule to plan a new one."
        TransferResult.Expired -> "This transfer's anchor expired. Reschedule to plan a new one."
        is TransferResult.Success -> error("migrationFailureMessage called with a Success result")
    }
