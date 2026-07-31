package co.electriccoin.zcash.ui.screen.connectkeystone.estimation

import kotlinx.serialization.Serializable

@Serializable
data class KeystoneEstimationArgs(
    val ur: String,
    val blockHeight: Long,
)
