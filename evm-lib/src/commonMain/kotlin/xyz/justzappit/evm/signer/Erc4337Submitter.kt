// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.div
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.math.times
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.BundlerClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.evm.util.hexToBigInteger
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.padLeftToWord
import xyz.justzappit.evm.util.toHex
import kotlin.time.TimeSource

/**
 * Sends each `{to, value, data}` as a gas-sponsored ERC-4337 v0.6 UserOperation. The owner key
 * signs locally (self-custody); the bundler (Pimlico) relays and its verifying paymaster pays. The
 * first op for an undeployed account carries the factory initCode (lazy deploy); subsequent ops
 * carry none.
 *
 * The returned [TxHash] is the userOpHash; [awaitReceipt] resolves it to the mined transaction
 * receipt via the bundler, whose inner logs are identical to a normal tx — so downstream log
 * parsing is unchanged from the EOA path.
 */
class Erc4337Submitter(
    private val rpc: BaseRpcClient,
    private val bundler: BundlerClient,
    private val entryPoint: Address,
    private val accountFactory: Address,
    private val owner: EvmKey,
    private val smartAccount: Address,
    private val chainId: ChainId,
    private val gasLimitBufferPercent: Int = DEFAULT_GAS_BUFFER_PCT,
    private val receiptTimeoutMs: Long = DEFAULT_RECEIPT_TIMEOUT_MS,
    private val receiptPollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : TxSubmitter {
    /**
     * Local nonce cursor. After a successful `eth_sendUserOperation`, the next sequential nonce is
     * deterministically `cursor + 1` — no RPC read needed. Sidesteps the cross-RPC race on
     * fast-block chains where Pimlico's simulator (which validates the nonce at sponsorship time)
     * has already advanced past what our node RPC reports, causing AA25 on back-to-back UserOps.
     * Null = uninitialized; populated by the first RPC read and incremented locally thereafter.
     */
    private var nonceCursor: BigInteger? = null

    /**
     * Operations this submitter has handed the bundler and not yet resolved. Each one is riding on a
     * nonce the cursor has already moved past, so the cursor cannot be invalidated while any of them
     * is still outstanding without handing the next send a nonce one of them already owns.
     */
    private val outstanding = mutableSetOf<TxHash>()
    private val unknownNonceOutstanding = mutableSetOf<TxHash>()
    private var hasUnidentifiedPendingTransaction = false

    /**
     * One send at a time per account. Reading the cursor, building the operation around it and
     * advancing it is a read-modify-write over several round trips; two callers interleaving inside
     * it sign two different operations against the same nonce, and the bundler keeps one.
     *
     * Only the send is serialised. Holding this across [awaitReceipt] would block every other
     * operation for the length of a confirmation, and sequential nonces queue at the bundler
     * perfectly well — but the bookkeeping [awaitReceipt] does at the end of a wait takes the lock
     * too, because the cursor is shared and a send may be building an operation around it.
     */
    private val sendLock = Mutex()

    override suspend fun sendTransaction(
        to: Address,
        value: Wei,
        data: ByteArray,
        beforeBroadcast: suspend (PreparedTransaction) -> Unit,
    ): TxHash = sendLock.withLock { send(to, value, data, beforeBroadcast) }

    private suspend fun send(
        to: Address,
        value: Wei,
        data: ByteArray,
        beforeBroadcast: suspend (PreparedTransaction) -> Unit,
    ): TxHash {
        check(!hasUnidentifiedPendingTransaction && unknownNonceOutstanding.isEmpty()) {
            "A legacy unresolved transaction blocks new sends from this smart account"
        }
        val initCode =
            if (rpc.ethGetCode(smartAccount).isEmpty()) {
                ThirdwebSmartAccount.initCode(accountFactory, owner.address)
            } else {
                ByteArray(0)
            }
        val gasPrice = bundler.getUserOperationGasPrice()
        val nonce = nonceCursor ?: entryPointNonce().also { nonceCursor = it }

        val draft =
            UserOperationV06(
                sender = smartAccount,
                nonce = nonce,
                initCode = initCode,
                callData = ThirdwebSmartAccount.executeCalldata(to, value, data),
                callGasLimit = bigIntegerZero,
                verificationGasLimit = bigIntegerZero,
                preVerificationGas = bigIntegerZero,
                maxFeePerGas = hexToBigInteger(gasPrice.maxFeePerGas),
                maxPriorityFeePerGas = hexToBigInteger(gasPrice.maxPriorityFeePerGas),
                paymasterAndData = ByteArray(0),
                signature = DUMMY_SIGNATURE,
            )

        // ERC-7677: estimate with a paymaster stub (so the estimate covers paymaster validation),
        // then request the real sponsorship. Stub and final paymasterAndData share a length, so the
        // gas estimate stays valid.
        val stubbed = draft.copy(paymasterAndData = bundler.getPaymasterStubData(draft).paymasterAndData.hexToBytes())
        val estimate = bundler.estimateUserOperationGas(stubbed)
        val withGas =
            stubbed.copy(
                callGasLimit = hexToBigInteger(estimate.callGasLimit).buffered(),
                verificationGasLimit = hexToBigInteger(estimate.verificationGasLimit).buffered(),
                preVerificationGas = hexToBigInteger(estimate.preVerificationGas).buffered(),
            )

        val sponsored =
            withGas.copy(
                paymasterAndData = bundler.sponsorUserOperation(withGas).paymasterAndData.hexToBytes(),
            )
        val userOpHash = sponsored.userOpHash(entryPoint, chainId)
        val signed = sponsored.copy(signature = signOwner(userOpHash))
        val txHash = TxHash.fromHex("0x${userOpHash.toHex()}")
        // This is the last local action before the network send. A recovery record written by the
        // callback identifies these exact signed fields, while a callback failure proves the
        // operation never reached the bundler.
        beforeBroadcast(PreparedTransaction(hash = txHash, nonce = nonce))
        // The durable marker now owns this nonce. Advance before making the network call: a request
        // can reach the bundler even when its response is lost, and reusing the nonce then would
        // replace the exact operation recovery is still tracking. A callback failure stays above
        // this boundary, so a marker that could not be stored advances nothing.
        nonceCursor = nonce + bigIntegerOne
        outstanding += txHash
        val returnedHash =
            try {
                bundler.sendUserOperation(signed)
            } catch (error: RpcException) {
                if (error.isDefiniteSendRejection()) {
                    // A JSON-RPC/HTTP rejection proves the bundler did not accept this operation.
                    // Reclaim its nonce while still holding sendLock. A transport failure does not
                    // enter here: the request may have landed and keeps its nonce ownership.
                    outstanding -= txHash
                    nonceCursor = if (outstanding.isEmpty()) null else nonce
                }
                throw error
            }
        check(returnedHash == txHash) {
            "Bundler returned $returnedHash for signed UserOperation $txHash"
        }
        return txHash
    }

    override suspend fun restorePendingTransaction(hash: TxHash?, nonce: BigInteger?) =
        sendLock.withLock {
            if (hash == null) {
                hasUnidentifiedPendingTransaction = true
                return@withLock
            }
            if (nonce == null) {
                unknownNonceOutstanding += hash
                return@withLock
            }
            val chainNonce = nonceCursor ?: entryPointNonce()
            if (chainNonce <= nonce) {
                outstanding += hash
                val afterPending = nonce + bigIntegerOne
                nonceCursor = if (chainNonce >= afterPending) chainNonce else afterPending
            } else {
                nonceCursor = chainNonce
            }
        }

    private fun RpcException.isDefiniteSendRejection(): Boolean =
        method == METHOD_SEND_USER_OPERATION && this !is RpcException.TransportError

    override suspend fun receiptIfIncluded(txHash: TxHash): TransactionReceipt? {
        val receipt = bundler.getUserOperationReceipt(txHash) ?: return null
        settle(txHash)
        return receipt
    }

    /**
     * Only an included receipt settles nonce ownership. A failed poll, cancellation, or timeout says
     * nothing about whether the bundler accepted the operation, so its hash stays [outstanding] and
     * the next send stays above its nonce. Reusing it would replace the exact identity recovery is
     * still tracking.
     */
    override suspend fun awaitReceipt(txHash: TxHash): TransactionReceipt {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < receiptTimeoutMs) {
            bundler.getUserOperationReceipt(txHash)?.let { receipt ->
                // Included consumes the nonce whether execution succeeded or reverted.
                settle(txHash)
                return receipt
            }
            delay(receiptPollIntervalMs)
        }
        val minutes = receiptTimeoutMs / 60_000
        error(
            "Bundler did not return a receipt for userOp ${txHash.hex} after ${minutes}m. " +
                "The operation may still confirm on-chain — check the explorer before retrying.",
        )
    }

    /** [NonCancellable] because an included operation must release its in-memory ownership. */
    private suspend fun settle(txHash: TxHash) =
        withContext(NonCancellable) {
            sendLock.withLock {
                outstanding -= txHash
                unknownNonceOutstanding -= txHash
                // An anonymous legacy blocker has no identity to pass to awaitReceipt, so it
                // deliberately remains until the host resolves or discards that recovery record.
            }
        }

    /**
     * EntryPoint.getNonce(sender, key=0): the next sequential nonce; 0 for a counterfactual account.
     * Read once per submitter instance — the cursor takes over after the first UserOp lands. Uses
     * the node RPC because Pimlico's bundler endpoint does not serve `eth_call`.
     */
    private suspend fun entryPointNonce(): BigInteger {
        val ret =
            rpc.ethCall(
                to = entryPoint,
                data =
                    AbiEncoder.encodeFunctionCall(
                        "getNonce(address,uint192)",
                        listOf(AbiAddress(smartAccount), AbiUint(bigIntegerZero)),
                    ),
            )
        return if (ret.isEmpty()) bigIntegerZero else BigInteger(1, ret)
    }

    /** thirdweb's prebuilt Account contract validates the owner's ECDSA signature over the EIP-191-prefixed userOpHash. */
    private fun signOwner(userOpHash: ByteArray): ByteArray {
        val ethHash = keccak256(EIP191_PREFIX + userOpHash)
        return encodeSignature(owner.signRecoverable(ethHash))
    }

    private fun BigInteger.buffered(): BigInteger =
        this * bigIntegerValueOf(100L + gasLimitBufferPercent) / bigIntegerValueOf(100L)

    companion object {
        private const val DEFAULT_GAS_BUFFER_PCT = 15
        private const val DEFAULT_RECEIPT_TIMEOUT_MS = 300_000L
        private const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        private const val V_OFFSET = 27
        private const val EIP191_BYTE: Byte = 0x19
        private const val METHOD_SEND_USER_OPERATION = "eth_sendUserOperation"

        private val EIP191_PREFIX =
            byteArrayOf(EIP191_BYTE) + "Ethereum Signed Message:\n32".encodeToByteArray()

        // A structurally valid (canonical, low-s) throwaway signature for gas estimation, before the
        // real userOpHash is known. Recovers to some address, not the owner — fine, estimation does
        // not enforce the signature, it only needs the right length so ECDSA.recover doesn't revert.
        private val DUMMY_SIGNATURE: ByteArray =
            encodeSignature(EcdsaSigner.sign(keccak256("estimate".encodeToByteArray()), bigIntegerOne))

        private fun encodeSignature(sig: EcdsaSignature): ByteArray =
            sig.r.toByteArray().padLeftToWord() + sig.s.toByteArray().padLeftToWord() +
                byteArrayOf((sig.yParity + V_OFFSET).toByte())
    }
}
