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

    override suspend fun swap(id: SwapId): OnChainSwap? = decodeSwap(rpc.call(deployment.contract, GET_SWAP, id))

    override suspend fun confirmedSwap(id: SwapId): OnChainSwap? {
        rpc.requireChain(deployment)
        return decodeSwap(rpc.call(deployment.contract, GET_SWAP, id, deployment.confirmedTag(rpc.head())))
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
    override suspend fun read(id: SwapId): ReverseChainState {
        rpc.requireChain(deployment)
        val head = rpc.head()
        val confirmed = deployment.confirmedTag(head)
        val funding = abiWords(rpc.call(deployment.contract, REVERSE_FUNDING, id, confirmed), 2)
        return ReverseChainState(
            swap = decodeSwap(rpc.call(deployment.contract, GET_SWAP, id, confirmed)),
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

    override suspend fun rescueNonce(id: SwapId): Long? =
        try {
            rpc.requireChain(deployment)
            abiWords(rpc.call(deployment.contract, "rescueNonces(bytes32)", id), 1).uint(0).toNonNegativeLongExact()
        } catch (_: RpcException.ExecutionReverted) {
            null
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

        /** The 16 words `getSwap` returns, or null for a swap that isn't open (stage 0). */
        fun decodeSwap(data: ByteArray): OnChainSwap? {
            val words = abiWords(data, Word.COUNT)
            val raw = words.uint8(Word.STAGE)
            // Stage 0 is None; 1 to 4 follow SwapStage's order.
            if (raw == 0) return null
            val stage =
                SwapStage.entries.getOrNull(raw - 1)
                    ?: throw AtomicSwapBlockedException(AtomicSwapBlock.CHAIN_UNREADABLE, "unknown swap stage $raw")
            return OnChainSwap(
                maker = words.address(Word.MAKER),
                t0 = words.uint(Word.T0).toNonNegativeLongExact(),
                stage = stage,
                paidOut = words.uint8(Word.PAID_OUT) != 0,
                user = words.address(Word.USER),
                t1 = words.uint(Word.T1).toNonNegativeLongExact(),
                token = words.address(Word.TOKEN),
                claimLockUntil = words.uint(Word.CLAIM_LOCK_UNTIL).toNonNegativeLongExact(),
                amount = Usdc6(words.uint(Word.AMOUNT)),
                refundLockUntil = words.uint(Word.REFUND_LOCK_UNTIL).toNonNegativeLongExact(),
                makerShare = SwapShare.of(words.word(Word.MAKER_X) + words.word(Word.MAKER_Y)),
                userShare = SwapShare.of(words.word(Word.USER_X) + words.word(Word.USER_Y)),
                secret = words.word(Word.SECRET),
                payoutNote = NoteCommitment.of(words.word(Word.PAYOUT_NOTE)),
            )
        }
    }

    /** `getSwap`'s static struct, word by word. */
    private object Word {
        const val MAKER = 0
        const val T0 = 1
        const val STAGE = 2
        const val PAID_OUT = 3
        const val USER = 4
        const val T1 = 5
        const val TOKEN = 6
        const val CLAIM_LOCK_UNTIL = 7
        const val AMOUNT = 8
        const val REFUND_LOCK_UNTIL = 9
        const val MAKER_X = 10
        const val MAKER_Y = 11
        const val USER_X = 12
        const val USER_Y = 13
        const val SECRET = 14
        const val PAYOUT_NOTE = 15
        const val COUNT = 16
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
