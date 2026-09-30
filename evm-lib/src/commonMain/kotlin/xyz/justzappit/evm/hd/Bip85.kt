// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.hd

/** BIP-85: entropy for another wallet, derived from a BIP-32 root that the other wallet never sees. */
object Bip85 {
    /**
     * The entropy of the [words]-word English BIP-39 mnemonic at `m/83696968'/39'/0'/{words}'/{index}'` below
     * the BIP-32 root of [seed]. The caller zeroizes it.
     */
    fun bip39Entropy(
        seed: ByteArray,
        words: Int,
        index: Int
    ): ByteArray {
        require(words in MIN_WORDS..MAX_WORDS step WORDS_STEP) { "a BIP-39 mnemonic has 12 to 24 words, in steps of 3" }
        require(index >= 0) { "index must be non-negative" }
        return bip39Entropy(Bip32.master(seed), words, index)
    }

    internal fun bip39Entropy(
        root: ExtendedKey,
        words: Int,
        index: Int
    ): ByteArray {
        val entropy = entropy(root, listOf(BIP39_APPLICATION, ENGLISH, words, index))
        return try {
            entropy.copyOf(words / WORDS_STEP * BYTES_PER_STEP)
        } finally {
            entropy.fill(0)
        }
    }

    /** `HMAC-SHA512("bip-entropy-from-k", k)`, for the key `k` at `m/83696968'` followed by [path], all hardened. */
    internal fun entropy(
        root: ExtendedKey,
        path: List<Int>
    ): ByteArray {
        val node = Bip32.derive(root, (listOf(PURPOSE) + path).map { it or Bip32.HARDENED })
        return try {
            platformHmacSha512(ENTROPY_KEY.encodeToByteArray(), node.privateKey)
        } finally {
            node.zeroize()
        }
    }

    private const val PURPOSE = 83_696_968
    private const val BIP39_APPLICATION = 39
    private const val ENGLISH = 0
    private const val ENTROPY_KEY = "bip-entropy-from-k"
    private const val MIN_WORDS = 12
    private const val MAX_WORDS = 24
    private const val WORDS_STEP = 3
    private const val BYTES_PER_STEP = 4
}
