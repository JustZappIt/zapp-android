// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.hd

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.signer.SECP256K1_N
import xyz.justzappit.evm.signer.secpPublicKeyUncompressed

/** A BIP-32 private node on secp256k1; whoever holds one zeroizes it. */
internal class ExtendedKey(
    val privateKey: ByteArray,
    val chainCode: ByteArray,
) {
    fun zeroize() {
        privateKey.fill(0)
        chainCode.fill(0)
    }
}

/** BIP-32 private derivation on secp256k1. */
internal object Bip32 {
    const val HARDENED: Int = 0x80000000.toInt()

    fun master(seed: ByteArray): ExtendedKey {
        val key = "Bitcoin seed".encodeToByteArray()
        val derived = platformHmacSha512(key, seed)
        return try {
            ExtendedKey(
                privateKey = derived.copyOfRange(0, FIELD_BYTES),
                chainCode = derived.copyOfRange(FIELD_BYTES, derived.size),
            )
        } finally {
            key.fill(0)
            derived.fill(0)
        }
    }

    /** The node at [path] below [root], which is zeroized along with every node in between. */
    fun derive(
        root: ExtendedKey,
        path: List<Int>
    ): ExtendedKey =
        path.fold(root) { parent, index ->
            try {
                ckdPrivWithRetry(parent, index)
            } finally {
                parent.zeroize()
            }
        }

    private fun ckdPrivWithRetry(
        parent: ExtendedKey,
        startIndex: Int
    ): ExtendedKey {
        var index = startIndex
        while (true) {
            val candidate = ckdPrivOnce(parent, index)
            if (candidate != null) return candidate
            val next = index + 1
            check(next != startIndex) { "BIP-32 ckdPriv: exhausted all 2^32 child indices" }
            index = next
        }
    }

    private fun ckdPrivOnce(
        parent: ExtendedKey,
        index: Int
    ): ExtendedKey? {
        val hardened = (index.toLong() and UNSIGNED_INT_MASK) >= HARDENED_THRESHOLD
        val data =
            if (hardened) {
                // `0x00 ‖ key ‖ index` in one array, so no copy of the key is left behind unwiped.
                ByteArray(1 + FIELD_BYTES + Int.SIZE_BYTES).also {
                    parent.privateKey.copyInto(it, destinationOffset = 1)
                    intToBytes(index).copyInto(it, destinationOffset = 1 + FIELD_BYTES)
                }
            } else {
                compressedPub(parent.privateKey) + intToBytes(index)
            }
        val derived = platformHmacSha512(parent.chainCode, data)
        val left = derived.copyOfRange(0, FIELD_BYTES)
        return try {
            val leftNumber = BigInteger(1, left)
            val child = (leftNumber + BigInteger(1, parent.privateKey)).mod(SECP256K1_N)
            if (leftNumber >= SECP256K1_N || child == bigIntegerZero) {
                null
            } else {
                ExtendedKey(
                    privateKey = child.toKeyBytes(),
                    chainCode = derived.copyOfRange(FIELD_BYTES, derived.size),
                )
            }
        } finally {
            data.fill(0)
            derived.fill(0)
            left.fill(0)
        }
    }

    private fun compressedPub(privateKey: ByteArray): ByteArray {
        val uncompressed = secpPublicKeyUncompressed(privateKey)
        val prefix = if (uncompressed.last().toInt() and 1 == 0) COMPRESSED_EVEN_PREFIX else COMPRESSED_ODD_PREFIX
        return byteArrayOf(prefix.toByte()) + uncompressed.copyOfRange(1, FIELD_BYTES + 1)
    }

    /** The key's 32 bytes; the minimal two's-complement bytes it's read from are wiped. */
    private fun BigInteger.toKeyBytes(): ByteArray {
        val minimal = toByteArray()
        return try {
            ByteArray(FIELD_BYTES).also {
                val skipped = (minimal.size - FIELD_BYTES).coerceAtLeast(0)
                minimal.copyInto(it, destinationOffset = FIELD_BYTES - minimal.size + skipped, startIndex = skipped)
            }
        } finally {
            minimal.fill(0)
        }
    }

    private fun intToBytes(value: Int): ByteArray =
        ByteArray(Int.SIZE_BYTES) { (value ushr Byte.SIZE_BITS * (Int.SIZE_BYTES - 1 - it)).toByte() }

    private const val FIELD_BYTES = 32
    private const val UNSIGNED_INT_MASK = 0xffff_ffffL
    private const val HARDENED_THRESHOLD = 0x8000_0000L
    private const val COMPRESSED_EVEN_PREFIX = 0x02
    private const val COMPRESSED_ODD_PREFIX = 0x03
}
