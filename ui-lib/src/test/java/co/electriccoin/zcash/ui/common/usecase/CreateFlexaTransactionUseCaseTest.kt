package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Flexa is told a transaction was sent only when the submission produced a real tx id and the
 * outcome means the transaction is on (or likely on) the network: a full success, or a resubmittable
 * GrpcFailure (timeout / gRPC-level rejection). Hard failures, partial sends, and missing tx ids
 * must surface as a failure instead so a paid commerce session is never completed incorrectly.
 */
class CreateFlexaTransactionUseCaseTest {
    @Test
    fun successReportsFirstTxId() {
        assertEquals("tx-success", SubmitResult.Success(txIds = listOf("tx-success")).flexaTransactionSignatureOrNull())
    }

    @Test
    fun grpcFailureReportsFirstTxId() {
        assertEquals(
            "tx-grpc",
            SubmitResult
                .GrpcFailure(
                    txIds = listOf("tx-grpc"),
                    reason = SubmitResult.GrpcFailure.Reason.TIMEOUT
                ).flexaTransactionSignatureOrNull()
        )
    }

    @Test
    fun grpcFailureWithoutTxIdReportsNothing() {
        assertNull(SubmitResult.GrpcFailure(txIds = emptyList()).flexaTransactionSignatureOrNull())
    }

    @Test
    fun partialReportsNothing() {
        assertNull(
            SubmitResult
                .Partial(txIds = listOf("tx-partial"), statuses = listOf("success", "notAttempted"))
                .flexaTransactionSignatureOrNull()
        )
    }

    @Test
    fun failureReportsNothing() {
        assertNull(
            SubmitResult
                .Failure(txIds = listOf("tx-failure"), code = 18, description = "rejected")
                .flexaTransactionSignatureOrNull()
        )
    }

    @Test
    fun errorReportsNothing() {
        assertNull(SubmitResult.Error(cause = RuntimeException("boom")).flexaTransactionSignatureOrNull())
    }
}
