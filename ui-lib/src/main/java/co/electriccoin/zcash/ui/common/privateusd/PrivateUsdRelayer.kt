// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.spackle.Twig
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.atomicswap.RailgunBroadcast
import xyz.justzappit.offramp.atomicswap.RailgunSendRelayer
import xyz.justzappit.offramp.atomicswap.RailgunSendsTerms
import xyz.justzappit.offramp.atomicswap.RailgunTransactRequest
import xyz.justzappit.offramp.atomicswap.RelayerTerms
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunBroadcaster
import xyz.justzappit.railgun.RailgunRelayRequest
import java.math.BigInteger

/**
 * What the app takes from a deployment's relayer for private sends. Without it, a gateway that rewrote the terms and
 * kept the post could take the fee note and send the transaction itself.
 */
data class RailgunSendsPin(
    val chainId: ChainId,
    val railgunProxy: Address,
    val railgunAddress: RailgunAddress,
    val feeToken: Address,
)

/** A deployment's relayer as the broadcaster of private sends and withdrawals, held to [pin]. */
class PrivateUsdRelayer(
    private val relayer: RailgunSendRelayer,
    private val pin: RailgunSendsPin,
) {
    /** Its terms now; null when it sends none, or none the pin allows. */
    suspend fun broadcaster(): RailgunBroadcaster? {
        val terms = relayer.terms()
        return terms.railgunSends?.let { pinned(terms, it) }
    }

    suspend fun send(request: RailgunRelayRequest): RailgunBroadcast =
        relayer.transact(
            RailgunTransactRequest(ChainId(request.chainId), request.to, request.data, request.value.toString())
        )

    // A rate comes with the time it's held until, or the terms are unreadable.
    private fun pinned(
        terms: RelayerTerms,
        sends: RailgunSendsTerms
    ): RailgunBroadcaster? {
        val maxGasPrice = positive(sends.maxGasPriceWei)
        val feePerUnitGas = sends.feePerUnitGas?.let(::positive)
        val isPinned =
            terms.chainId == pin.chainId &&
                sends.railgunProxy == pin.railgunProxy &&
                RailgunAddress.parseOrNull(sends.railgunAddress) == pin.railgunAddress &&
                sends.token == pin.feeToken
        val isRateReadable = sends.feePerUnitGas == null || (feePerUnitGas != null && sends.feeExpiresAt != null)
        if (!isPinned || maxGasPrice == null || !isRateReadable) {
            Twig.warn { "Private USD: the relayer's terms for sends aren't the pinned ones" }
            return null
        }
        return RailgunBroadcaster(
            chainId = pin.chainId.value,
            railgunProxy = pin.railgunProxy,
            railgunAddress = pin.railgunAddress,
            feeToken = pin.feeToken,
            minFee = sends.fee.micros,
            maxGasPrice = maxGasPrice,
            feePerUnitGas = feePerUnitGas,
            feeExpiresAt = sends.feeExpiresAt.takeIf { feePerUnitGas != null },
        )
    }

    private fun positive(decimal: String): BigInteger? =
        decimal
            .takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?.toBigInteger()
            ?.takeIf { it.signum() > 0 }
}
