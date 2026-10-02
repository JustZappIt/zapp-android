// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.Nonce
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import java.math.BigInteger
import kotlin.test.assertEquals

class GasAccountTransactionsTest {
    @Test
    fun `a consumed nonce without a receipt cannot prove a payment failed`() =
        runTest {
            val raw = "0x02f8"
            val hash = TxHash(keccak256(raw.hexToBytes()))
            val from = Address.parse("0x" + "12".repeat(20))
            val rpc = mockk<BaseRpcClient>()
            coEvery { rpc.ethGetTransactionCount(from, "latest") } returns Nonce(BigInteger.ONE)
            coEvery { rpc.ethGetTransactionReceipt(hash) } returns null
            coEvery { rpc.ethGetTransactionByHash(hash) } returns null

            assertEquals(GasAccountDelivery.UNCONFIRMED, GasAccountTransactions(rpc).reconcile(raw, hash, from, 0))
            coVerify(exactly = 0) { rpc.ethSendRawTransaction(any()) }
        }

    @Test
    fun `already known with a lagging hash lookup must remain unconfirmed`() =
        runTest {
            val raw = "0x02f8"
            val hash = TxHash(keccak256(raw.hexToBytes()))
            val rpc = mockk<BaseRpcClient>()
            coEvery { rpc.ethSendRawTransaction(raw) } throws
                RpcException.Unknown("eth_sendRawTransaction", -32000, "", "already known")
            coEvery { rpc.ethGetTransactionByHash(hash) } returns null
            assertEquals(GasAccountDelivery.UNCONFIRMED, GasAccountTransactions(rpc).deliver(raw, hash))
        }
}
