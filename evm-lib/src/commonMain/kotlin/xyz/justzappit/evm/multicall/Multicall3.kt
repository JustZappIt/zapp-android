// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.multicall

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiArray
import xyz.justzappit.evm.abi.AbiBool
import xyz.justzappit.evm.abi.AbiBytes
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiTuple
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex

/**
 * Multicall3 (`mds1/multicall`): several contract reads in one `eth_call`, every one of them answered as of the same
 * block. That is what a read must be when its parts are judged together, such as a swap's state and the time of the
 * block it was read in: two separate reads from a load-balanced node can answer a block apart.
 *
 * [aggregate3] runs each [Multicall3Call] in order and answers each with a [Multicall3Result]. A call that may fail
 * ([Multicall3Call.allowFailure]) answers its revert with `success = false`; one that may not reverts the whole read.
 */
object Multicall3 {
    /** Where Multicall3 is deployed, the same address on Ethereum, Sepolia and every chain its deployer reached. */
    val ADDRESS: Address = Address.parse("0xcA11bde05977b3631167028862bE2a173976CA11")

    /** `aggregate3((address,bool,bytes)[])`, whose answer is `(bool success, bytes returnData)[]`. */
    const val AGGREGATE3 = "aggregate3((address,bool,bytes)[])"

    /** A call answered with the time of the block the whole read ran in: one `uint256`, in unix seconds. */
    fun currentBlockTimestamp(): Multicall3Call =
        Multicall3Call(ADDRESS, AbiEncoder.encodeFunctionCall("getCurrentBlockTimestamp()", emptyList()))

    /** A call answered with the number of the block the whole read ran in: one `uint256`. */
    fun blockNumber(): Multicall3Call =
        Multicall3Call(ADDRESS, AbiEncoder.encodeFunctionCall("getBlockNumber()", emptyList()))

    /** The calldata of `aggregate3` running [calls], in order. */
    fun encodeAggregate3(calls: List<Multicall3Call>): ByteArray =
        AbiEncoder.encodeFunctionCall(
            AGGREGATE3,
            listOf(
                AbiArray(
                    calls.map { call ->
                        AbiTuple(listOf(AbiAddress(call.target), AbiBool(call.allowFailure), AbiBytes(call.callData)))
                    },
                ),
            ),
        )

    /**
     * `aggregate3`'s answer to [calls] calls, one result each in their order. Throws [IllegalArgumentException] on an
     * answer that doesn't read as exactly that many results, rather than reading past it or guessing.
     */
    fun decodeAggregate3(
        data: ByteArray,
        calls: Int,
    ): List<Multicall3Result> {
        require(calls >= 0) { "a negative number of calls" }
        val answer = Words(data)
        val array = answer.offset(0)
        val count = answer.offset(array)
        require(count == calls.toLong()) { "aggregate3 answered $count results to $calls calls" }
        val elements = array + WORD
        return List(calls) { index ->
            val tuple = elements + answer.offset(elements + index * WORD)
            val success = answer.bool(tuple)
            val bytes = tuple + answer.offset(tuple + WORD)
            Multicall3Result(success, answer.bytes(bytes))
        }
    }
}

/** One read of an aggregate: [callData] sent to [target]; where [allowFailure], its revert leaves the rest standing. */
class Multicall3Call(
    val target: Address,
    callData: ByteArray,
    val allowFailure: Boolean = false,
) {
    val callData: ByteArray = callData.copyOf()

    override fun equals(other: Any?): Boolean =
        other is Multicall3Call &&
            target == other.target &&
            allowFailure == other.allowFailure &&
            callData.contentEquals(other.callData)

    override fun hashCode(): Int =
        (target.hashCode() * HASH + allowFailure.hashCode()) * HASH + callData.contentHashCode()

    override fun toString(): String =
        "Multicall3Call(target=$target, callData=0x${callData.toHex()}, allowFailure=$allowFailure)"
}

/** One read's answer: what it returned where it [success]fully ran, else its revert data. */
class Multicall3Result(
    val success: Boolean,
    returnData: ByteArray,
) {
    val returnData: ByteArray = returnData.copyOf()

    /** What a read that had to succeed returned, as [count] ABI words or more. */
    fun words(count: Int): AbiDecoder {
        require(success) { "the call reverted" }
        return AbiDecoder(returnData).also { it.requireWords(count) }
    }

    /** What a read that had to succeed returned, one `uint256`. */
    fun uint(): BigInteger = words(1).uint(0)

    override fun equals(other: Any?): Boolean =
        other is Multicall3Result && success == other.success && returnData.contentEquals(other.returnData)

    override fun hashCode(): Int = success.hashCode() * HASH + returnData.contentHashCode()

    override fun toString(): String = "Multicall3Result(success=$success, returnData=0x${returnData.toHex()})"
}

/** [calls] in one `eth_call` to Multicall3 at [blockTag], every one answered as of the same block. */
suspend fun BaseRpcClient.aggregate3(
    calls: List<Multicall3Call>,
    blockTag: String = "latest",
): List<Multicall3Result> =
    Multicall3.decodeAggregate3(ethCall(Multicall3.ADDRESS, Multicall3.encodeAggregate3(calls), blockTag), calls.size)

/** An ABI answer read by byte offsets, each checked to lie within it. */
private class Words(
    private val data: ByteArray,
) {
    /** The word at [at] as an offset or a length: one that can't lie within the answer doesn't read. */
    fun offset(at: Long): Long {
        val word = word(at)
        val significant = word.copyOfRange(0, WORD - LONG_BYTES)
        require(significant.all { it == 0.toByte() }) { "an offset or length past the answer" }
        val value =
            word.copyOfRange(WORD - LONG_BYTES, WORD).fold(0L) { acc, byte ->
                (acc shl BYTE_BITS) or (byte.toLong() and BYTE_MASK)
            }
        require(value in 0..data.size) { "an offset or length past the answer" }
        return value
    }

    /** The word at [at] as a `bool`: exactly 0 or 1. */
    fun bool(at: Long): Boolean {
        val word = word(at)
        require(word.copyOfRange(0, WORD - 1).all { it == 0.toByte() } && word[WORD - 1] in 0..1) { "not a bool" }
        return word[WORD - 1] == 1.toByte()
    }

    /** The `bytes` whose length word is at [at]. */
    fun bytes(at: Long): ByteArray {
        val length = offset(at)
        val start = at + WORD
        require(start + length <= data.size) { "bytes past the answer" }
        return data.copyOfRange(start.toInt(), (start + length).toInt())
    }

    private fun word(at: Long): ByteArray {
        require(at >= 0 && at + WORD <= data.size) { "a word past the answer" }
        return data.copyOfRange(at.toInt(), (at + WORD).toInt())
    }
}

private const val WORD = AbiDecoder.WORD
private const val LONG_BYTES = Long.SIZE_BYTES
private const val BYTE_BITS = Byte.SIZE_BITS
private const val BYTE_MASK = 0xFFL
private const val HASH = 31
