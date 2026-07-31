// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.abi

import xyz.justzappit.evm.util.padLeftToWord
import xyz.justzappit.evm.math.bigIntegerValueOf

object AbiEncoder {
    fun encode(args: List<AbiArg>): ByteArray {
        val staticSize = args.size * WORD
        val tails = args.map { if (it.isDynamic) it.tail() else EMPTY_BYTES }
        val out = ByteArray(staticSize + tails.sumOf(ByteArray::size))
        var writeOffset = 0
        var dynOffset = staticSize

        args.forEachIndexed { index, arg ->
            val head = if (arg.isDynamic) {
                bigIntegerValueOf(dynOffset.toLong()).toByteArray().padLeftToWord().also {
                    dynOffset += tails[index].size
                }
            } else {
                arg.head()
            }
            head.copyInto(out, writeOffset)
            writeOffset += head.size
        }
        tails.forEach { tail ->
            tail.copyInto(out, writeOffset)
            writeOffset += tail.size
        }
        return out
    }

    fun encodeFunctionCall(canonicalSignature: String, args: List<AbiArg>): ByteArray =
        Selector4.fromCanonicalSignature(canonicalSignature).bytes + encode(args)

    fun bytes32String(s: String): AbiBytes32 {
        val data = s.encodeToByteArray()
        require(data.size <= WORD) { "string too long for bytes32 (${data.size} bytes): '$s'" }
        return AbiBytes32(data + ByteArray(WORD - data.size))
    }
}

private val EMPTY_BYTES = ByteArray(0)
