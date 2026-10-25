// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The third audit's port spec, replayed: in every case its oracle decides (`windows-spec-vectors.json`, copied byte for
 * byte from zecSwap's `crates/zecswap-chain/tests/` at 88f8dfe), whether a party may reveal now on one read, the
 * judgements the app makes agree, as zecSwap's `tests/windows_spec.rs` replays them for the relayer and its clients.
 * The maker's forward reveals are its own policy's, and are left out.
 */
class WindowsSpecTest {
    @Test
    fun everyClaimRefundAndReadyIsJudgedAsThePortSpecDecides() {
        val vectors = vectors()
        assertEquals(VECTORS, vectors.size)
        val wrong = mutableListOf<String>()
        val replayed = mutableMapOf<String, Int>()
        vectors.forEachIndexed { index, vector ->
            // The depths, margin and signature life the spec decides on are the ones the app waits for.
            assertEquals(READY_DEPTH, vector.margins.readyDepth)
            assertEquals(REVERSE_READY_DEPTH, vector.margins.reverseReadyDepth)
            assertEquals(CLAIM_MARGIN_SECONDS, vector.margins.claimApp)
            assertEquals(SIGNATURE_TTL_SECONDS, vector.margins.readySigTtl)
            val send = maySend(vector) ?: return@forEachIndexed
            replayed[vector.reveal] = (replayed[vector.reveal] ?: 0) + 1
            if (send != vector.expect.send) {
                val expect = vector.expect
                wrong += "#$index ${vector.reveal}: sends $send, the spec says ${expect.send} (${expect.reason})"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        assertEquals(REPLAYED, replayed.values.sum(), "every claim, refund and ready the spec decides")
        // The app's own: its forward claims, its reverse refunds, and its readies.
        assertEquals(
            mapOf("user_claim" to 25, "user_refund" to 12, "user_ready" to 7),
            replayed.filterKeys { it.startsWith("user_") },
        )
    }

    /** Whether the reveal goes now, as the app and the relayer judge it, or none for the maker's forward reveals. */
    private fun maySend(vector: Vector): Boolean? {
        val stage = stage(vector.view.stage)
        return when {
            vector.reveal in MAKERS_FORWARD -> null

            // No swap on the chain: nothing to reveal.
            stage == null -> false

            vector.reveal in CLAIMS -> mayClaim(vector, stage)

            else -> mayRefundOrReady(vector, stage)
        }
    }

    private fun mayClaim(
        vector: Vector,
        stage: SwapStage,
    ): Boolean {
        val view = vector.view
        val windows = SwapWindows(view.minWindow, view.lateWindow)
        val head = ChainHead(view.headNumber, view.headTs)
        val margin = if (vector.reveal == "user_claim") vector.margins.claimApp else vector.margins.claimRelayer
        val judged =
            if (vector.reveal == "maker_claim") {
                windows.reverseClaim(stage, view.t0, view.t1, head, view.wallNow, margin)
            } else {
                windows.forwardClaim(stage, view.t0, view.t1, head, view.wallNow, margin)
            }
        // A ready the swap shows as of `block`, where the judgement waits for one there.
        val goes =
            when (judged) {
                Go.Now -> true
                is Go.OnceReadyAt -> view.readyBlock?.let { it <= judged.block } == true
                is Refusal -> false
            }
        val isForward = view.direction == "forward"
        val isTheirs = if (vector.reveal == "maker_claim") !isForward else isForward && view.railgunAccepts
        return goes && isTheirs
    }

    private fun mayRefundOrReady(
        vector: Vector,
        stage: SwapStage,
    ): Boolean {
        val view = vector.view
        val head = ChainHead(view.headNumber, view.headTs)
        val isReverse = view.direction != "forward"
        return when (val reveal = vector.reveal) {
            "user_refund", "relayer_refund" -> {
                val pending = reveal == "relayer_refund" && vector.own.ownReadyPending
                val signedUntil = vector.own.readySigDeadlines.maxOrNull()
                val windows = SwapWindows(view.minWindow, view.lateWindow)
                isReverse && windows.reverseRefund(stage, view.t0, view.t1, head, signedUntil, pending) == null
            }

            "user_ready" -> {
                isReverse && !vector.own.refundSent && view.depositConfirmed &&
                    reverseReady(stage, view.t0, head, vector.margins.readySigTtl) == null
            }

            else -> {
                error("no reveal $reveal")
            }
        }
    }

    private fun stage(stage: String): SwapStage? =
        when (stage) {
            "open" -> SwapStage.OPEN
            "ready" -> SwapStage.READY
            "claimed" -> SwapStage.CLAIMED
            "refunded" -> SwapStage.REFUNDED
            "none" -> null
            else -> error("no stage $stage")
        }

    private fun vectors(): List<Vector> {
        val text = checkNotNull(javaClass.classLoader.getResource("windows-spec-vectors.json")).readText()
        return json.decodeFromString(ListSerializer(Vector.serializer()), text)
    }

    @Serializable
    private class Vector(
        val reveal: String,
        val view: View,
        val own: Own,
        val margins: Margins,
        val expect: Expect,
    )

    /** One read: the swap at the newest block, that block, and the wall clock. */
    @Serializable
    private class View(
        val direction: String,
        val stage: String,
        @SerialName("head_ts") val headTs: Long,
        @SerialName("head_number") val headNumber: Long,
        @SerialName("wall_now") val wallNow: Long,
        val t0: Long,
        val t1: Long,
        @SerialName("min_window") val minWindow: Long,
        @SerialName("late_window") val lateWindow: Long,
        @SerialName("ready_block") val readyBlock: Long?,
        @SerialName("finalized_number") val finalizedNumber: Long,
        @SerialName("railgun_accepts") val railgunAccepts: Boolean,
        @SerialName("deposit_confirmed") val depositConfirmed: Boolean,
        @SerialName("wallet_synced") val walletSynced: Boolean,
    )

    /** What the party recorded before sending. */
    @Serializable
    private class Own(
        @SerialName("ready_sent") val readySent: Boolean,
        @SerialName("cancel_sent") val cancelSent: Boolean,
        @SerialName("refund_sent") val refundSent: Boolean,
        @SerialName("ready_sig_deadlines") val readySigDeadlines: List<Long>,
        @SerialName("own_ready_pending") val ownReadyPending: Boolean,
    )

    @Serializable
    private class Margins(
        val cancel: Long,
        @SerialName("refund_late_stop") val refundLateStop: Long,
        @SerialName("claim_app") val claimApp: Long,
        @SerialName("claim_relayer") val claimRelayer: Long,
        @SerialName("ready_land") val readyLand: Long,
        @SerialName("ready_sig_ttl") val readySigTtl: Long,
        @SerialName("ready_depth") val readyDepth: Long,
        @SerialName("reverse_ready_depth") val reverseReadyDepth: Long,
    )

    @Serializable
    private class Expect(
        val send: Boolean,
        val reason: String,
        val edge: Long?,
        val start: Long?,
    )

    private companion object {
        const val VECTORS = 97
        const val REPLAYED = 61
        val MAKERS_FORWARD = setOf("maker_ready", "maker_cancel", "maker_refund")
        val CLAIMS = setOf("user_claim", "relayer_claim", "maker_claim")

        // Strict, so a field the spec adds or renames fails here rather than going unread.
        val json = Json { ignoreUnknownKeys = false }
    }
}
