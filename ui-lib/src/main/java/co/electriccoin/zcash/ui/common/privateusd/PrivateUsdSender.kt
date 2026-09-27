// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunSent
import java.math.BigInteger

enum class PrivateUsdSendMode { PRIVATE, WITHDRAW }

data class PrivateUsdSendRequest(
    val mode: PrivateUsdSendMode,
    val token: PrivateUsdToken,
    val amount: BigInteger,
    val to: String,
)

data class PrivateUsdSendCost(
    /** Railgun's fee on a withdrawal, taken from the amount sent. */
    val railgunFee: BigInteger,
    /** A broadcaster's fee on top, in the same token; null while a test account pays the gas. */
    val broadcasterFee: BigInteger?,
) {
    fun received(amount: BigInteger): BigInteger = amount - railgunFee
}

/** Sends from the Railgun balance. Railgun's broadcasters take the testnet gas account's place. */
interface PrivateUsdSender {
    val usesTestAccount: Boolean

    suspend fun cost(request: PrivateUsdSendRequest): PrivateUsdSendCost

    suspend fun send(request: PrivateUsdSendRequest): RailgunSent
}

/** Pays gas from a funded Sepolia account, which links each send to that account: testnets only. */
class TestnetGasAccountSender(
    private val railgunWalletRepository: RailgunWalletRepository,
) : PrivateUsdSender {
    override val usesTestAccount = true

    override suspend fun cost(request: PrivateUsdSendRequest) =
        PrivateUsdSendCost(
            railgunFee =
                if (request.mode == PrivateUsdSendMode.WITHDRAW) {
                    request.amount * UNSHIELD_FEE_BPS / BPS
                } else {
                    BigInteger.ZERO
                },
            broadcasterFee = null,
        )

    override suspend fun send(request: PrivateUsdSendRequest): RailgunSent =
        railgunWalletRepository.send(
            to = request.to,
            token = request.token.address,
            amount = request.amount,
            withdraw = request.mode == PrivateUsdSendMode.WITHDRAW,
        )

    private companion object {
        val UNSHIELD_FEE_BPS: BigInteger = BigInteger.valueOf(25)
        val BPS: BigInteger = BigInteger.valueOf(10_000)
    }
}

/** The sender this build has; none until broadcasters exist for its network. */
class PrivateUsdSenders(
    railgunWalletRepository: RailgunWalletRepository,
) {
    val current: PrivateUsdSender? =
        when (railgunWalletRepository.state.value.network) {
            RailgunNetwork.SEPOLIA -> TestnetGasAccountSender(railgunWalletRepository)
            RailgunNetwork.MAINNET, null -> null
        }
}
