package co.electriccoin.zcash.ui.screen.swap.upi.scan

import kotlinx.serialization.Serializable
import xyz.justzappit.offramp.p2p.CurrencyCode
import java.util.UUID

@Serializable
data class ScanUpiArgs(
    val requestId: String = UUID.randomUUID().toString(),
    // The corridor to validate the scan against (INR/BRL/IDR), as the CurrencyCode.code string.
    val currencyCode: String = CurrencyCode.Inr.code,
)
