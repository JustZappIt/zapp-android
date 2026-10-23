// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.math.toNonNegativeLongExact
import xyz.justzappit.evm.multicall.Multicall3
import xyz.justzappit.evm.multicall.Multicall3Call
import xyz.justzappit.evm.multicall.Multicall3Result
import xyz.justzappit.evm.multicall.aggregate3
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.EvmLog
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.rpc.transactionStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBigInteger
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * [deployment]'s contract and Railgun's proxy over JSON-RPC, read for swaps either way. No swap is read before the
 * contract shows it is the one the deployment pins: a windows contract answers `MIN_WINDOW()` and `LATE_WINDOW()` as
 * pinned and reverts `LOCK_DURATION()`, which only the lock contract answers, and whose six-word swap records would
 * otherwise read as the windows contract's four.
 */
@Suppress("TooManyFunctions")
class AtomicSwapChain(
    private val rpc: BaseRpcClient,
    private val deployment: SwapDeployment,
) : AtomicSwapChainReader,
    ReverseSwapChain {
    // The contract's identity is immutable: once it checks out, it is never read again.
    private var isIdentified = false

    override suspend fun swap(id: SwapId): SwapState? {
        requireIdentity()
        return decodeState(rpc.call(deployment.contract, GET_SWAP, id), deployment.windows)
    }

    override suspend fun confirmedSwap(
        id: SwapId,
        terms: SwapTerms
    ): OnChainSwap? {
        rpc.requireChain(deployment)
        requireIdentity()
        val confirmed = deployment.confirmedTag(rpc.head())
        return decodeSwap(rpc.call(deployment.contract, GET_SWAP, id, confirmed), terms, deployment.windows)
    }

    // One eth_call: the swap, the block's time and its number all come from the block the node answers it at.
    override suspend fun swapNow(
        id: SwapId,
        terms: SwapTerms
    ): SwapRead {
        rpc.requireChain(deployment)
        requireIdentity()
        val windows =
            deployment.windows
                ?: throw AtomicSwapBlockedException(AtomicSwapBlock.RETIRED_CONTRACT, "a lock contract has no windows")
        val getSwap = Multicall3Call(deployment.contract, swapCall(GET_SWAP, id))
        val (swap, time, number) =
            rpc.aggregate3(listOf(getSwap, Multicall3.currentBlockTimestamp(), Multicall3.blockNumber()))
        val head = ChainHead(number.uint().toNonNegativeLongExact(), time.uint().toNonNegativeLongExact())
        return SwapRead(decodeSwap(swap.returnData, terms, windows), head)
    }

    override suspend fun swapAt(
        id: SwapId,
        terms: SwapTerms,
        block: Long,
    ): OnChainSwap? {
        rpc.requireChain(deployment)
        requireIdentity()
        return decodeSwap(rpc.call(deployment.contract, GET_SWAP, id, blockTag(block)), terms, deployment.windows)
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

    // The contract keys a spent share by its owner and the share, hashed as a swap id is.
    override suspend fun makerKeyUsed(
        owner: Address,
        share: SwapShare
    ): Boolean = abiWords(rpc.call(deployment.contract, MAKER_KEY_USED, SwapId.of(owner, share)), 1).uint8(0) != 0

    override suspend fun confirmedPayout(
        id: SwapId,
        since: Long
    ): SwapPayoutEvidence? {
        rpc.requireChain(deployment)
        val log = confirmedLog(PAID_OUT_TOPIC, id, since) ?: return null
        val words = abiWords(log.data.hexToBytes(), 2)
        return SwapPayoutEvidence(TxHash.fromHex(log.transactionHash), words.address(0), Usdc6(words.uint(1)))
    }

    override suspend fun refundedAt(
        id: SwapId,
        since: Long
    ): Long? {
        rpc.requireChain(deployment)
        return confirmedLog(REFUNDED_TOPIC, id, since)?.let { rpc.head(it.blockNumber).timestamp }
    }

    // Read far enough behind the head that the escrow has its confirmations.
    override suspend fun read(
        id: SwapId,
        terms: SwapTerms
    ): ReverseChainState {
        rpc.requireChain(deployment)
        requireIdentity()
        val head = rpc.head()
        val confirmed = deployment.confirmedTag(head)
        val funding = abiWords(rpc.call(deployment.contract, REVERSE_FUNDING, id, confirmed), 2)
        return ReverseChainState(
            swap = decodeSwap(rpc.call(deployment.contract, GET_SWAP, id, confirmed), terms, deployment.windows),
            refundNote = NoteCommitment.of(funding.word(0)),
            fundingBlock = funding.uint(1).toNonNegativeLongExact(),
            block = head.number,
            now = head.timestamp,
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

    // MIN_WINDOW() and LATE_WINDOW() revert on the lock contract, and LOCK_DURATION() on the windows contract: all
    // three are asked at once, each allowed to revert.
    private suspend fun requireIdentity() {
        if (isIdentified) return
        val asked = listOf(MIN_WINDOW, LATE_WINDOW, LOCK_DURATION).map { Multicall3Call(deployment.contract, it, true) }
        val (min, late, lock) = rpc.aggregate3(asked)
        val windows = deployment.windows
        val isPinned =
            if (windows == null) {
                (lock.seconds() ?: 0) > 0 && !min.success
            } else {
                !lock.success && min.seconds() == windows.min && late.seconds() == windows.late
            }
        if (!isPinned) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "the contract isn't the one pinned")
        }
        isIdentified = true
    }

    // Public nodes cap a log query's range. Walk backwards in bounded pages, including delayed events after [since].
    private suspend fun confirmedLog(
        topic: String,
        id: SwapId,
        since: Long,
    ): EvmLog? {
        val head = rpc.head()
        val confirmed = deployment.confirmedNumber(head)
        val elapsed = head.timestamp - since.coerceIn(0, head.timestamp)
        val around = head.number - elapsed / SECONDS_PER_BLOCK
        val earliest = (around - LOG_WINDOW_BLOCKS).coerceIn(0, confirmed)
        var to = confirmed
        while (to >= earliest) {
            val from = (to - LOG_WINDOW_BLOCKS + 1).coerceAtLeast(earliest)
            val logs = rpc.ethGetLogs(deployment.contract, listOf(topic, id.hex), fromBlock = from, toBlock = to)
            val log = logs.firstOrNull { it.isFor(topic, id, from..to) }
            if (log != null) return log
            to = from - 1
        }
        return null
    }

    private fun EvmLog.isFor(
        topic: String,
        id: SwapId,
        blocks: LongRange,
    ): Boolean =
        !removed && address.equals(deployment.contract.lowercaseHex, ignoreCase = true) &&
            topics.size == 2 && topics[0].equals(topic, ignoreCase = true) &&
            topics[1].equals(id.hex, ignoreCase = true) &&
            hexToBigInteger(blockNumber).toNonNegativeLongExact() in blocks

    companion object {
        private const val GET_SWAP = "getSwap(bytes32)"
        private const val MAKER_KEY_USED = "makerKeyUsed(bytes32)"
        private const val REVERSE_FUNDING = "reverseFunding(bytes32)"
        private const val VAULT_OF = "vaultOf(bytes32)"
        private const val SECONDS_PER_BLOCK = 12
        private const val LOG_WINDOW_BLOCKS = 5_000L
        private val PAID_OUT_TOPIC = keccak256("PaidOut(bytes32,address,uint256)".encodeToByteArray()).hex()
        private val REFUNDED_TOPIC = keccak256("Refunded(bytes32,uint256)".encodeToByteArray()).hex()
        private val MIN_WINDOW = AbiEncoder.encodeFunctionCall("MIN_WINDOW()", emptyList())
        private val LATE_WINDOW = AbiEncoder.encodeFunctionCall("LATE_WINDOW()", emptyList())
        private val LOCK_DURATION = AbiEncoder.encodeFunctionCall("LOCK_DURATION()", emptyList())

        /**
         * `getSwap`'s answer, or null for no swap (stage 0): four words from a windows contract, six from a lock one
         * with no [windows], as the deployment says and never as the answer's length does. A shorter answer is
         * [AtomicSwapBlock.CHAIN_UNREADABLE]; a longer one, another contract's.
         */
        fun decodeState(
            data: ByteArray,
            windows: SwapWindows?,
        ): SwapState? {
            val layout = if (windows != null) WindowsWords else LockWords
            val words = abiWords(data, layout.count)
            if (data.size != layout.count * AbiDecoder.WORD) {
                throw AtomicSwapBlockedException(AtomicSwapBlock.WRONG_DEPLOYMENT, "getSwap gave ${data.size} bytes")
            }
            val raw = words.uint8(STAGE)
            // Stage 0 is None; 1 to 4 follow SwapStage's order.
            if (raw == 0) return null
            val stage =
                SwapStage.entries.getOrNull(raw - 1)
                    ?: throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_UNREADABLE, "unknown swap stage $raw")
            return SwapState(
                termsHash = words.word(TERMS_HASH),
                stage = stage,
                paidOut = words.uint8(PAID_OUT) != 0,
                secret = words.word(layout.secret),
            )
        }

        /** The swap [terms] describe, once `getSwap` holds their hash; null for no swap. */
        fun decodeSwap(
            data: ByteArray,
            terms: SwapTerms,
            windows: SwapWindows?,
        ): OnChainSwap? = decodeState(data, windows)?.verified(terms)

        // `getSwap`'s static struct, word by word: the windows contract's ends with the revealed share, and the lock
        // contract's keeps its two locks before it.
        private const val TERMS_HASH = 0
        private const val STAGE = 1
        private const val PAID_OUT = 2

        private sealed class Layout(
            val count: Int,
            val secret: Int,
        )

        private data object WindowsWords : Layout(count = 4, secret = 3)

        private data object LockWords : Layout(count = 6, secret = 5)
    }
}

/** The answer's one word as a count of seconds, where the call answered one that fits; else null. */
private fun Multicall3Result.seconds(): Long? =
    returnData
        .takeIf { success && it.size == AbiDecoder.WORD }
        ?.let { AbiDecoder(it).uint(0) }
        ?.takeIf { it.bitLength() < Long.SIZE_BITS }
        ?.toLong()

private class Head(
    val number: Long,
    val timestamp: Long,
)

private suspend fun BaseRpcClient.head(blockTag: String = "latest"): Head =
    ethGetBlockByNumber(blockTag).let {
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

private fun SwapDeployment.confirmedTag(head: Head) = blockTag(confirmedNumber(head))

private fun blockTag(block: Long): String {
    require(block >= 0) { "a block number is never negative" }
    return "0x" + block.toString(HEX_RADIX)
}

private const val HEX_RADIX = 16

private fun swapCall(
    signature: String,
    id: SwapId,
): ByteArray = AbiEncoder.encodeFunctionCall(signature, listOf(AbiBytes32(id.bytes)))

private suspend fun BaseRpcClient.call(
    contract: Address,
    signature: String,
    id: SwapId,
    blockTag: String = "latest",
): ByteArray = ethCall(contract, swapCall(signature, id), blockTag)

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
