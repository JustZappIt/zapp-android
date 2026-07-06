package co.electriccoin.zcash.ui.common.model.migration

import kotlinx.serialization.Serializable

@Serializable
enum class MigrationDeliveryMode {
    /** Transfers broadcast automatically in the background via WorkManager. */
    SCHEDULED,

    /** Background delivery is unavailable — the user must open the app and confirm each transfer. */
    MANUAL,
}
