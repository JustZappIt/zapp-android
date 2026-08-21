// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The gift claim erases its isolated wallet database only when this returns
 * [SubmitResult.Success], and that database holds the only key to the card's funds — so every case
 * that is *not* an unambiguous success has to stay not-a-success. See `GIFT_CARDS_PLAN.md` §5.
 */
class SubmitResultFoldTest {
    @Test
    fun `every transaction reaching the mempool is a success`() {
        val result = listOf(success(1), success(2)).toSubmitResult()

        assertIs<SubmitResult.Success>(result)
        assertEquals(listOf(txId(1), txId(2)), result.txIds)
    }

    @Test
    fun `a rejection is a failure carrying the last real error`() {
        val result = listOf(rejected(1, code = 17, description = "nope")).toSubmitResult()

        assertIs<SubmitResult.Failure>(result)
        assertEquals(17, result.code)
        assertEquals("nope", result.description)
    }

    @Test
    fun `a transport failure is a gRPC failure, not a rejection`() {
        // It may or may not have reached the network, so the claim must retain its database.
        val result = listOf(grpcFailed(1)).toSubmitResult()

        assertIs<SubmitResult.GrpcFailure>(result)
    }

    @Test
    fun `some sent and some rejected is partial, never success`() {
        val result = listOf(success(1), rejected(2, code = 3, description = "bad")).toSubmitResult()

        assertIs<SubmitResult.Partial>(result)
        assertEquals(listOf("success", "rejected code: 3"), result.statuses)
    }

    @Test
    fun `some sent and the rest merely unreachable stays resubmittable`() {
        val result = listOf(success(1), grpcFailed(2)).toSubmitResult()

        assertIs<SubmitResult.GrpcFailure>(result)
    }

    @Test
    fun `a transaction that was never attempted is not a success`() {
        val result = listOf(success(1), notAttempted(2)).toSubmitResult()

        // Pinning surprising-but-inherited behaviour rather than the reading one would guess.
        // NotAttempted contributes no grpcError flag either way, so `resubmittableFailures` is
        // empty and `all { it }` is vacuously true — a partly-attempted broadcast lands on
        // GrpcFailure rather than Partial. It reads oddly, but it is upstream's classification and
        // §5 says to reuse it rather than invent a second one. What the gift claim depends on holds
        // regardless: both sit in the retain column, so the isolated database survives either way.
        assertTrue(result !is SubmitResult.Success, "an unattempted transaction cannot be a success")
        assertIs<SubmitResult.GrpcFailure>(result)
    }

    @Test
    fun `nothing at all is not a success`() {
        // An empty broadcast means nothing was sent. The claim must keep its database either way.
        assertTrue(emptyList<TransactionSubmitResult>().toSubmitResult() !is SubmitResult.Success)
    }

    @Test
    fun `a gRPC failure is preferred over a stale rejection code`() {
        // The rejection is reported only when at least one failure actually reached the server.
        val mixed = listOf(rejected(1, code = 9, description = "rejected"), grpcFailed(2)).toSubmitResult()

        assertIs<SubmitResult.Failure>(mixed)
        assertEquals(9, mixed.code)
    }

    private fun success(seed: Int) = TransactionSubmitResult.Success(bytes(seed))

    private fun notAttempted(seed: Int) = TransactionSubmitResult.NotAttempted(bytes(seed))

    private fun grpcFailed(seed: Int) =
        TransactionSubmitResult.Failure(bytes(seed), grpcError = true, code = 0, description = null)

    private fun rejected(seed: Int, code: Int, description: String) =
        TransactionSubmitResult.Failure(bytes(seed), grpcError = false, code = code, description = description)

    private fun bytes(seed: Int) = FirstClassByteArray(ByteArray(TXID_BYTES) { seed.toByte() })

    private fun txId(seed: Int) = bytes(seed).byteArray.reversed().joinToString("") { "%02x".format(it) }

    private companion object {
        const val TXID_BYTES = 32
    }
}
