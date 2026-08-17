// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.abi

/** Ethereum's legacy Keccak-256 (not the NIST SHA3-256 padding variant). */
fun keccak256(data: ByteArray): ByteArray {
    val state = LongArray(LANE_COUNT)
    var offset = 0
    while (data.size - offset >= RATE_BYTES) {
        absorbBlock(state, data, offset)
        keccakF1600(state)
        offset += RATE_BYTES
    }

    val finalBlock = ByteArray(RATE_BYTES)
    data.copyInto(finalBlock, endIndex = data.size, startIndex = offset)
    finalBlock[data.size - offset] = KECCAK_SUFFIX
    finalBlock[RATE_BYTES - 1] = (finalBlock[RATE_BYTES - 1].toInt() xor FINAL_BIT).toByte()
    absorbBlock(state, finalBlock, 0)
    keccakF1600(state)

    return ByteArray(DIGEST_BYTES).also { output ->
        for (i in output.indices) {
            output[i] = (state[i / BYTES_PER_LANE] ushr (BYTE_BITS * (i % BYTES_PER_LANE))).toByte()
        }
    }
}

private fun absorbBlock(state: LongArray, block: ByteArray, offset: Int) {
    for (lane in 0 until RATE_LANES) {
        var value = 0L
        for (byteIndex in 0 until BYTES_PER_LANE) {
            val byte = block[offset + lane * BYTES_PER_LANE + byteIndex].toLong() and BYTE_MASK
            value = value or (byte shl (BYTE_BITS * byteIndex))
        }
        state[lane] = state[lane] xor value
    }
}

private fun keccakF1600(state: LongArray) {
    val column = LongArray(DIMENSION)
    val mixed = LongArray(LANE_COUNT)
    for (roundConstant in ROUND_CONSTANTS) {
        for (x in 0 until DIMENSION) {
            column[x] = state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
        }
        for (x in 0 until DIMENSION) {
            val delta = column[(x + 4) % DIMENSION] xor rotateLeft(column[(x + 1) % DIMENSION], 1)
            for (y in 0 until DIMENSION) state[x + DIMENSION * y] = state[x + DIMENSION * y] xor delta
        }

        for (x in 0 until DIMENSION) {
            for (y in 0 until DIMENSION) {
                val targetX = y
                val targetY = (2 * x + 3 * y) % DIMENSION
                mixed[targetX + DIMENSION * targetY] =
                    rotateLeft(state[x + DIMENSION * y], ROTATION[x + DIMENSION * y])
            }
        }

        for (x in 0 until DIMENSION) {
            for (y in 0 until DIMENSION) {
                val index = x + DIMENSION * y
                val next = mixed[(x + 1) % DIMENSION + DIMENSION * y]
                val nextNext = mixed[(x + 2) % DIMENSION + DIMENSION * y]
                state[index] = mixed[index] xor (next.inv() and nextNext)
            }
        }
        state[0] = state[0] xor roundConstant
    }
}

private fun rotateLeft(value: Long, bits: Int): Long =
    if (bits == 0) value else (value shl bits) or (value ushr (Long.SIZE_BITS - bits))

private const val DIMENSION = 5
private const val LANE_COUNT = DIMENSION * DIMENSION
private const val RATE_BYTES = 136
private const val RATE_LANES = RATE_BYTES / 8
private const val DIGEST_BYTES = 32
private const val BYTES_PER_LANE = 8
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xffL
private const val KECCAK_SUFFIX: Byte = 0x01
private const val FINAL_BIT = 0x80

private val ROTATION =
    intArrayOf(
        0,
        1,
        62,
        28,
        27,
        36,
        44,
        6,
        55,
        20,
        3,
        10,
        43,
        25,
        39,
        41,
        45,
        15,
        21,
        8,
        18,
        2,
        61,
        56,
        14,
    )

private val ROUND_CONSTANTS =
    longArrayOf(
        0x0000000000000001UL.toLong(),
        0x0000000000008082UL.toLong(),
        0x800000000000808aUL.toLong(),
        0x8000000080008000UL.toLong(),
        0x000000000000808bUL.toLong(),
        0x0000000080000001UL.toLong(),
        0x8000000080008081UL.toLong(),
        0x8000000000008009UL.toLong(),
        0x000000000000008aUL.toLong(),
        0x0000000000000088UL.toLong(),
        0x0000000080008009UL.toLong(),
        0x000000008000000aUL.toLong(),
        0x000000008000808bUL.toLong(),
        0x800000000000008bUL.toLong(),
        0x8000000000008089UL.toLong(),
        0x8000000000008003UL.toLong(),
        0x8000000000008002UL.toLong(),
        0x8000000000000080UL.toLong(),
        0x000000000000800aUL.toLong(),
        0x800000008000000aUL.toLong(),
        0x8000000080008081UL.toLong(),
        0x8000000000008080UL.toLong(),
        0x0000000080000001UL.toLong(),
        0x8000000080008008UL.toLong(),
    )
