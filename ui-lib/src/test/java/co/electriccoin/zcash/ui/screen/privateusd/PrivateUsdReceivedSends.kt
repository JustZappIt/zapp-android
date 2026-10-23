// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdReceived
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.common.privateusd.Sepolia
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunBalanceBucket
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunReceivedSend
import java.math.BigInteger
import kotlin.time.Instant

/** A private send received in Sepolia's USDC, a testnet dollar, in a block made at [at], in Unix seconds. */
internal fun receivedSend(
    at: Long,
    bucket: RailgunBalanceBucket? = RailgunBalanceBucket.SPENDABLE,
    sender: RailgunAddress? = null,
    amount: BigInteger = BigInteger.valueOf(5_000_000),
    txid: TxHash = TxHash.fromHex("%064x".format(at)),
) = RailgunReceivedSend(txid, Sepolia.USDC, amount, bucket, Instant.fromEpochSeconds(at), sender)

/** [send] as the balance keeps it. */
internal fun received(send: RailgunReceivedSend) =
    PrivateUsdReceived(
        send = send,
        token = checkNotNull(PrivateUsdTokens.find(RailgunNetwork.SEPOLIA, send.token)),
        at = checkNotNull(send.blockTime),
        key = "received-${send.txid.hex}",
    )

internal val SENDER =
    RailgunAddress(
        "0zk1qyt5x0c632363rrmd8psxws6n9tscm8gps277gzc0w3s5cg4mdpe9rv7j6fe3z53" +
            "luahk4ksjwagt68fl2vguye054rxjyqzvhs9usq4rwrk09al6n0677pdrgn"
    )
