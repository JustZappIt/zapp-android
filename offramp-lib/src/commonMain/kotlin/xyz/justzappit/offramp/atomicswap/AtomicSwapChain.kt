// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.math.toNonNegativeLongExact
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.rpc.transactionStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBigInteger
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.offramp.p2p.Usdc6

/** [deployment]'s contract and Railgun's proxy over JSON-RPC, read for swaps either way. */
@Suppress("TooManyFunctions")
class AtomicSwapChain(
    private val rpc: BaseRpcClient,
    private val deployment: SwapDeployment,
) : AtomicSwapChainReader,
    ReverseSwapChain {
    // Immutable in the contract, so it is read once.
    private var lockDuration: Long? = null

    override suspend fun swap(id: SwapId): SwapState? = decodeState(rpc.call(deployment.contract, GET_SWAP, id))

    override suspend fun confirmedSwap(
        id: SwapId,
        terms: SwapTerms
    ): OnChainSwap? {
        rpc.requireChain(deployment)
        return decodeSwap(rpc.call(deployment.contract, GET_SWAP, id, deployment.confirmedTag(rpc.head())), terms)
    }

    override suspend fun now(): Long = rpc.head().timestamp

    override suspend fun railgunAccepts(token: Address): Boolean =
        try {
            val call = AbiEncoder.encodeFunctionCall("tokenBlocklist(address)", listOf(AbiAddress(token)))
            abiWords(rpc.ethCall(deployment.railgunProxy, call), 1).uint8(0) == 0
        } catch (_: RpcException.ExecutionReverted) {
            // Railgun's proxy reverts every call while it is paused.
            false
        }

    override suspend fun lockDuration(): Long =
        lockDuration ?: abiWords(rpc.ethCall(deployment.contract, LOCK_DURATION_CALL), 1)
            .uint(0)
            .toNonNegativeLongExact()
            .also { lockDuration = it }

    // The contract keys a spent share by its owner and the share, hashed as a swap id is.
    override suspend fun makerKeyUsed(
        owner: Address,
        share: SwapShare
    ): Boolean = abiWords(rpc.call(deployment.contract, MAKER_KEY_USED, SwapId.of(owner, share)), 1).uint8(0) != 0

    // Public nodes cap a log query's range. Walk backwards in bounded pages, including delayed payouts after [since].
    override suspend fun confirmedPayout(
        id: SwapId,
        since: Long
    ): SwapPayoutEvidence? {
        rpc.requireChain(deployment)
        val head = rpc.head()
        val confirmed = deployment.confirmedNumber(head)
        val elapsed = head.timestamp - since.coerceIn(0, head.timestamp)
        val around = head.number - elapsed / SECONDS_PER_BLOCK
        val earliest = (around - LOG_WINDOW_BLOCKS).coerceIn(0, confirmed)
        var to = confirmed
        while (to >= earliest) {
            val from = (to - LOG_WINDOW_BLOCKS + 1).coerceAtLeast(earliest)
            val log =
                rpc
                    .ethGetLogs(
                        address = deployment.contract,
                        topics = listOf(PAID_OUT_TOPIC, id.hex),
                        fromBlock = from,
                        toBlock = to,
                    ).firstOrNull {
                        !it.removed && it.address.equals(deployment.contract.lowercaseHex, ignoreCase = true) &&
                            it.topics.size == 2 && it.topics[0].equals(PAID_OUT_TOPIC, ignoreCase = true) &&
                            it.topics[1].equals(id.hex, ignoreCase = true) &&
                            hexToBigInteger(it.blockNumber).toNonNegativeLongExact() in from..to
                    }
            if (log != null) {
                val words = abiWords(log.data.hexToBytes(), 2)
                return SwapPayoutEvidence(TxHash.fromHex(log.transactionHash), words.address(0), Usdc6(words.uint(1)))
            }
            to = from - 1
        }
        return null
    }

    // Read far enough behind the head that the escrow has its confirmations.
    override suspend fun read(
        id: SwapId,
        terms: SwapTerms
    ): ReverseChainState {
        rpc.requireChain(deployment)
        val head = rpc.head()
        val confirmed = deployment.confirmedTag(head)
        val funding = abiWords(rpc.call(deployment.contract, REVERSE_FUNDING, id, confirmed), 2)
        return ReverseChainState(
            swap = decodeSwap(rpc.call(deployment.contract, GET_SWAP, id, confirmed), terms),
            refundNote = NoteCommitment.of(funding.word(0)),
            fundingBlock = funding.uint(1).toNonNegativeLongExact(),
            block = head.number,
            now = head.timestamp,
            lockDuration = lockDuration(),
        )
    }

    override suspend fun fundingStatus(transaction: TxHash): TransactionStatus =
        rpc.transactionStatus(transaction, deployment.escrowConfirmations)

    override suspend fun vaultBalance(id: SwapId): Usdc6 {
        val vault = abiWords(rpc.call(deployment.contract, VAULT_OF, id), 1).address(0)
        val balance = AbiEncoder.encodeFunctionCall("balanceOf(address)", listOf(AbiAddress(vault)))
        return Usdc6(abiWords(rpc.ethCall(deployment.token, balance), 1).uint(0))
    }

    override suspend fun rescueNonce(id: SwapId): Long {
        rpc.requireChain(deployment)
        return abiWords(rpc.call(deployment.contract, "rescueNonces(bytes32)", id), 1).uint(0).toNonNegativeLongExact()
    }

    companion object {
        private const val GET_SWAP = "getSwap(bytes32)"
        private const val MAKER_KEY_USED = "makerKeyUsed(bytes32)"
        private const val REVERSE_FUNDING = "reverseFunding(bytes32)"
        private const val VAULT_OF = "vaultOf(bytes32)"
        private const val SECONDS_PER_BLOCK = 12
        private const val LOG_WINDOW_BLOCKS = 5_000L
        private val PAID_OUT_TOPIC = keccak256("PaidOut(bytes32,address,uint256)".encodeToByteArray()).hex()
        private val LOCK_DURATION_CALL = AbiEncoder.encodeFunctionCall("LOCK_DURATION()", emptyList())

        /** `getSwap`'s six words, or null for no swap (stage 0); another length is another ABI's contract. */
        fun decodeState(data: ByteArray): SwapState? {
            val words = abiWords(data, Word.COUNT)
            if (data.size != Word.COUNT * AbiDecoder.WORD) {
                throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "getSwap gave ${data.size} bytes")
            }
            val raw = words.uint8(Word.STAGE)
            // Stage 0 is None; 1 to 4 follow SwapStage's order.
            if (raw == 0) return null
            val stage =
                SwapStage.entries.getOrNull(raw - 1)
                    ?: throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_UNREADABLE, "unknown swap stage $raw")
            return SwapState(
                termsHash = words.word(Word.TERMS_HASH),
                stage = stage,
                paidOut = words.uint8(Word.PAID_OUT) != 0,
                claimLockUntil = words.uint(Word.CLAIM_LOCK_UNTIL).toNonNegativeLongExact(),
                refundLockUntil = words.uint(Word.REFUND_LOCK_UNTIL).toNonNegativeLongExact(),
                secret = words.word(Word.SECRET),
            )
        }

        /** The swap [terms] describe, once `getSwap` holds their hash; null for no swap. */
        fun decodeSwap(
            data: ByteArray,
            terms: SwapTerms
        ): OnChainSwap? = decodeState(data)?.verified(terms)
    }

    /** `getSwap`'s static struct, word by word. */
    private object Word {
        const val TERMS_HASH = 0
        const val STAGE = 1
        const val PAID_OUT = 2
        const val CLAIM_LOCK_UNTIL = 3
        const val REFUND_LOCK_UNTIL = 4
        const val SECRET = 5
        const val COUNT = 6
    }
}

private class Head(
    val number: Long,
    val timestamp: Long,
)

private suspend fun BaseRpcClient.head(): Head =
    ethGetBlockByNumber().let {
        Head(
            hexToBigInteger(it.number).toNonNegativeLongExact(),
            hexToBigInteger(it.timestamp).toNonNegativeLongExact(),
        )
    }

private suspend fun BaseRpcClient.requireChain(deployment: SwapDeployment) {
    if (ethChainId() != deployment.chainId) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "the node serves another chain")
    }
}

private fun SwapDeployment.confirmedNumber(head: Head): Long {
    require(escrowConfirmations > 0) { "confirmation depth must be positive" }
    return (head.number - escrowConfirmations + 1).coerceAtLeast(0)
}

private fun SwapDeployment.confirmedTag(head: Head) = "0x" + confirmedNumber(head).toString(HEX_RADIX)

private const val HEX_RADIX = 16

private suspend fun BaseRpcClient.call(
    contract: Address,
    signature: String,
    id: SwapId,
    blockTag: String = "latest",
): ByteArray = ethCall(contract, AbiEncoder.encodeFunctionCall(signature, listOf(AbiBytes32(id.bytes))), blockTag)

/** [data] read as [count] words or more; a shorter answer from the node is [AtomicSwapBlock.CHAIN_UNREADABLE]. */
private fun abiWords(
    data: ByteArray,
    count: Int
): AbiDecoder {
    if (data.size < count * AbiDecoder.WORD) {
        throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_UNREADABLE, "the node answered ${data.size} bytes")
    }
    return AbiDecoder(data)
}
