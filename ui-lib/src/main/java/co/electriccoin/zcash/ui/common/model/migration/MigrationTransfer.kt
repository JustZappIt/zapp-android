package co.electriccoin.zcash.ui.common.model.migration

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class MigrationTransfer(
    val index: Int,
    val amountZatoshi: Long,
    val scheduledAtEpochSeconds: Long,
    val status: MigrationTransferStatus = MigrationTransferStatus.PENDING,
    // Defaults to 0 (always-expired) for plans persisted before this field existed — those are
    // dev-only mock data, never real migration state, so treating them as stale is correct.
    val expiryAtEpochSeconds: Long = 0L,
    // The transfer's real, stable MigrationTxId (see TransferProposal.id) — NOT the same ordering
    // as [index]. The engine assigns real tx ids in its own funding-note/crossing order (ZIP 318
    // deliberately shuffles that away from broadcast order), while [index] is this transfer's
    // position in the broadcast-height-sorted array the app displays as "Transfer N". The two
    // orderings are both stable but permanently different, so live SDK state (keyed by id) must be
    // correlated back to a displayed transfer via this field, never via [index]. Defaults to ""
    // for plans persisted before this field existed (dev-only mock data / pre-existing in-progress
    // plans) — live-state correlation simply finds no match for those, same as a plan with no
    // matching live SDK state at all.
    val id: String = "",
) {
    val scheduledAt: Instant get() = Instant.fromEpochSeconds(scheduledAtEpochSeconds)
    val expiryAt: Instant get() = Instant.fromEpochSeconds(expiryAtEpochSeconds)
}
