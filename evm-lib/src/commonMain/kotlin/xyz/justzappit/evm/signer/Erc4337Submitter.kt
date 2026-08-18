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
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.evm.util.hexToBigInteger
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.padLeftToWord
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

    override suspend fun sendTransaction(to: Address, value: Wei, data: ByteArray): TxHash =
        sendLock.withLock { send(to, value, data) }

    private suspend fun send(to: Address, value: Wei, data: ByteArray): TxHash {
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
        val signed = sponsored.copy(signature = signOwner(sponsored.userOpHash(entryPoint, chainId)))
        val txHash = bundler.sendUserOperation(signed)
        // Advance optimistically so back-to-back sends don't collide on the same nonce. Only a
        // submission whose fate stays unknown, with nothing else riding on the cursor, takes it back.
        nonceCursor = nonce + bigIntegerOne
        outstanding += txHash
        return txHash
    }

    /**
     * A poll that throws and a caller cancelled mid-wait leave the operation's fate as unknown as a
     * timeout does, so every exit settles. A hash left behind in [outstanding] is never taken out
     * again, and holds the cursor against invalidation for the life of the process.
     */
    override suspend fun awaitReceipt(txHash: TxHash): TransactionReceipt {
        val started = TimeSource.Monotonic.markNow()
        // An included operation consumed its nonce whether or not its execution reverted, so the
        // cursor stays where it is: re-reading the chain would hand the next send a nonce an
        // operation already queued behind this one is using.
        var wasIncluded = false
        try {
            while (started.elapsedNow().inWholeMilliseconds < receiptTimeoutMs) {
                bundler.getUserOperationReceipt(txHash)?.let { receipt ->
                    wasIncluded = true
                    return receipt
                }
                delay(receiptPollIntervalMs)
            }
        } finally {
            settle(txHash, invalidateCursor = !wasIncluded)
        }
        val minutes = receiptTimeoutMs / 60_000
        error(
            "Bundler did not return a receipt for userOp ${txHash.hex} after ${minutes}m. " +
                "The operation may still confirm on-chain — check the explorer before retrying.",
        )
    }

    /** [NonCancellable] because taking the lock suspends, and a cancelled caller must still release its hash. */
    private suspend fun settle(txHash: TxHash, invalidateCursor: Boolean) =
        withContext(NonCancellable) {
            sendLock.withLock {
                outstanding -= txHash
                // With nothing else riding on the cursor, force a re-read rather than AA25-storming
                // against a value nothing can confirm.
                if (invalidateCursor && outstanding.isEmpty()) nonceCursor = null
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
