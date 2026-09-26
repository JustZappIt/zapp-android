// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBigInteger

/** [AtomicSwapChainReader] over JSON-RPC: the ZecSwap contract and Railgun's proxy. */
class AtomicSwapChain(
    private val rpc: BaseRpcClient,
    private val contract: Address,
    private val railgunProxy: Address,
) : AtomicSwapChainReader {
    override suspend fun swap(id: ByteArray): OnChainSwap? =
        decodeSwap(rpc.ethCall(contract, AbiEncoder.encodeFunctionCall("getSwap(bytes32)", listOf(AbiBytes32(id)))))

    override suspend fun now(): Long = hexToBigInteger(rpc.ethGetBlockByNumber().timestamp).toLong()

    override suspend fun railgunAccepts(token: Address): Boolean =
        try {
            val call = AbiEncoder.encodeFunctionCall("tokenBlocklist(address)", listOf(AbiAddress(token)))
            AbiDecoder(rpc.ethCall(railgunProxy, call)).uint8(0) == 0
        } catch (_: RpcException.ExecutionReverted) {
            // Railgun's proxy reverts every call while it is paused.
            false
        }

    companion object {
        private const val SHARE_BYTES = 64
        private const val ADDRESS_WORD_PADDING = 12

        /** `keccak256(maker ‖ userX ‖ userY)`, the maker's address as a 32-byte word. */
        fun swapId(
            maker: Address,
            userShare: ByteArray
        ): ByteArray {
            require(userShare.size == SHARE_BYTES) { "a share is $SHARE_BYTES bytes" }
            return keccak256(ByteArray(ADDRESS_WORD_PADDING) + maker.bytes + userShare)
        }

        /** The 16 words `getSwap` returns, or null for a swap that isn't open (stage 0). */
        fun decodeSwap(data: ByteArray): OnChainSwap? {
            val words = AbiDecoder(data).apply { requireWords(Word.COUNT) }
            val raw = words.uint8(Word.STAGE)
            // Stage 0 is None; 1 to 4 follow SwapStage's order.
            if (raw == 0) return null
            val stage = checkNotNull(SwapStage.entries.getOrNull(raw - 1)) { "unknown swap stage $raw" }
            return OnChainSwap(
                maker = words.address(Word.MAKER),
                t0 = words.uint(Word.T0).toLong(),
                stage = stage,
                paidOut = words.uint8(Word.PAID_OUT) != 0,
                user = words.address(Word.USER),
                t1 = words.uint(Word.T1).toLong(),
                token = words.address(Word.TOKEN),
                claimLockUntil = words.uint(Word.CLAIM_LOCK_UNTIL).toLong(),
                amount = words.uint(Word.AMOUNT),
                refundLockUntil = words.uint(Word.REFUND_LOCK_UNTIL).toLong(),
                makerShare = words.word(Word.MAKER_X) + words.word(Word.MAKER_Y),
                userShare = words.word(Word.USER_X) + words.word(Word.USER_Y),
                secret = words.word(Word.SECRET),
                payoutNote = words.word(Word.PAYOUT_NOTE),
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
