// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.funding.FundingOutcome
import xyz.justzappit.offramp.funding.OfframpFunding
import xyz.justzappit.offramp.funding.OfframpRefund
import xyz.justzappit.offramp.funding.OfframpTopUp
import xyz.justzappit.offramp.orchestrator.OfframpRequest
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getUsdcBalance

/**
 * iOS-owned half of the mainnet NEAR bridge. The app already owns its Zcash proposal/submission
 * pipeline and the 1-Click client, so the KMP engine deliberately asks the host to prepare and run
 * that bridge instead of duplicating wallet signing code.
 *
 * [prepare] MUST only create a quote and return its deposit address. [execute] may move ZEC. The
 * adapter persists the returned address through `onBridgeStarted` between those calls, preserving
 * the Android crash-safety invariant. [resume] only polls an existing address and must never quote
 * or send again.
 */
interface AppleOfframpBridge {
    @Throws(Exception::class)
    suspend fun prepare(accountAddress: String, usdcMicros: String): String

    @Throws(Exception::class)
    suspend fun execute(depositAddress: String): AppleBridgeExecution

    suspend fun resume(depositAddress: String): AppleBridgeExecution

    /** Base deposit address for a USDC -> ZEC 1-Click quote. */
    @Throws(Exception::class)
    suspend fun prepareRefund(accountAddress: String, usdcMicros: String): String
}

data class AppleBridgeExecution(
    val succeeded: Boolean,
    val terminal: Boolean = false,
    val message: String? = null,
)

class AppleBridgeTerminalException(
    message: String
) : IllegalStateException(message)

internal class AppleBridgeFunding(
    private val rpc: BaseRpcClient,
    private val usdc: Address,
    private val bridge: AppleOfframpBridge,
) : OfframpFunding,
    OfframpTopUp {
    override suspend fun ensureFunded(
        account: Address,
        request: OfframpRequest,
        resumeHandle: String?,
        onBridgeStarted: suspend (depositAddress: String) -> Unit,
    ): FundingOutcome {
        val balance = rpc.getUsdcBalance(usdc, account)
        if (balance >= request.usdcAmount) return FundingOutcome.AlreadyFunded(balance)
        return bridge(account, request.usdcAmount, resumeHandle, onBridgeStarted, verifyBalance = true)
    }

    override suspend fun bridge(
        account: Address,
        usdc: Usdc6,
        resumeHandle: String?,
        onBridgeStarted: suspend (depositAddress: String) -> Unit,
    ): FundingOutcome = bridge(account, usdc, resumeHandle, onBridgeStarted, verifyBalance = false)

    private suspend fun bridge(
        account: Address,
        amount: Usdc6,
        resumeHandle: String?,
        onBridgeStarted: suspend (depositAddress: String) -> Unit,
        verifyBalance: Boolean,
    ): FundingOutcome {
        val handle = resumeHandle ?: bridge.prepare(account.checksumHex, amount.micros.toString())
        onBridgeStarted(handle)
        val execution = if (resumeHandle == null) bridge.execute(handle) else bridge.resume(handle)
        check(execution.succeeded) {
            val message = execution.message ?: "NEAR bridge did not settle"
            if (execution.terminal) throw AppleBridgeTerminalException(message)
            message
        }
        if (verifyBalance) {
            check(rpc.getUsdcBalance(usdc, account) >= amount) {
                "NEAR bridge settled but ${account.checksumHex} is still under-funded for the order."
            }
        }
        return FundingOutcome.Bridged(handle)
    }
}

internal class AppleBridgeRefund(
    private val bridge: AppleOfframpBridge,
    private val onCheckpoint: suspend (amount: Usdc6, handle: String, transferStarted: Boolean, txHash: String?) -> Unit,
) : OfframpRefund {
    override suspend fun pullbackTarget(account: Address, amount: Usdc6): Address {
        val handle = bridge.prepareRefund(account.checksumHex, amount.micros.toString())
        onCheckpoint(amount, handle, false, null)
        return Address.parse(handle)
    }

    override suspend fun markTransferStarting(handle: String, amount: Usdc6) {
        onCheckpoint(amount, handle, true, null)
    }

    override suspend fun markTransferSubmitted(handle: String, amount: Usdc6, txHash: TxHash) {
        onCheckpoint(amount, handle, true, txHash.hex)
    }

    override suspend fun awaitSettlement(handle: String) {
        val execution = bridge.resume(handle)
        check(execution.succeeded) {
            val message = execution.message ?: "NEAR refund bridge did not settle"
            if (execution.terminal) throw AppleBridgeTerminalException(message)
            message
        }
    }
}

internal fun usdcFromMicros(value: String): Usdc6 = Usdc6(BigInteger(value))
