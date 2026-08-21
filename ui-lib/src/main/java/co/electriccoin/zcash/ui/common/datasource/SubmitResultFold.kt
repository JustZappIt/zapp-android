// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.zcash.ui.common.model.SubmitResult

/**
 * Collapses the per-transaction results of a broadcast into the single verdict the app acts on.
 *
 * A proposal can contain several transactions, so `createProposedTransactions` returns one result
 * per transaction and "did it send?" is not a boolean. Distinguishing the partial and
 * never-reached-the-server cases from success is what stops the gift claim erasing an isolated
 * wallet database whose funds have not actually moved (see `docs/GIFT_CARDS_PLAN.md` §5).
 *
 * Extracted from `ProposalDataSourceImpl.submitTransactionInternal` — which hardcodes the *main*
 * synchronizer and so cannot be reused for a claim — precisely so that the claim does not grow a
 * second copy of this logic. Two divergent copies is how a partial broadcast eventually reads as a
 * success somewhere. Pure, so both paths are covered by `SubmitResultFoldTest` on the JVM.
 */
@Suppress("CyclomaticComplexMethod")
internal fun List<TransactionSubmitResult>.toSubmitResult(): SubmitResult {
    val successCount = count { it is TransactionSubmitResult.Success }
    val txIds = map { it.txIdString() }
    val statuses =
        map {
            when (it) {
                is TransactionSubmitResult.Success -> {
                    "success"
                }

                is TransactionSubmitResult.Failure -> {
                    if (it.grpcError) {
                        GRPC_FAILURE_STATUS
                    } else {
                        "$REJECTED_STATUS_PREFIX${it.code}"
                    }
                }

                is TransactionSubmitResult.NotAttempted -> {
                    "notAttempted"
                }
            }
        }

    // Only Failure carries the flag; a NotAttempted contributes nothing either way, which is why
    // this maps to null rather than false. An all-true list means every failure was a transport
    // failure and the transactions may still be resubmittable.
    val resubmittableFailures =
        mapNotNull {
            when (it) {
                is TransactionSubmitResult.Failure -> it.grpcError
                is TransactionSubmitResult.NotAttempted -> null
                is TransactionSubmitResult.Success -> null
            }
        }

    val (errCode, errDesc) =
        filterIsInstance<TransactionSubmitResult.Failure>()
            .lastOrNull { !it.grpcError }
            ?.let { it.code to it.description } ?: (0 to "")

    return when (successCount) {
        0 -> {
            if (resubmittableFailures.all { it }) {
                SubmitResult.GrpcFailure(txIds = txIds)
            } else {
                SubmitResult.Failure(txIds = txIds, code = errCode, description = errDesc)
            }
        }

        txIds.size -> {
            SubmitResult.Success(txIds = txIds)
        }

        else -> {
            if (resubmittableFailures.all { it }) {
                SubmitResult.GrpcFailure(txIds = txIds)
            } else {
                SubmitResult.Partial(txIds = txIds, statuses = statuses)
            }
        }
    }
}

private const val GRPC_FAILURE_STATUS = "grpcFailure"
private const val REJECTED_STATUS_PREFIX = "rejected code: "
