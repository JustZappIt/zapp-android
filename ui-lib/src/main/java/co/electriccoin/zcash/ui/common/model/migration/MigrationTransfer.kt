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
) {
    val scheduledAt: Instant get() = Instant.fromEpochSeconds(scheduledAtEpochSeconds)
    val expiryAt: Instant get() = Instant.fromEpochSeconds(expiryAtEpochSeconds)
}
