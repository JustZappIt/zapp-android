// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.padLeftToWord
import xyz.justzappit.evm.util.toHex

/**
 * Signs onramp requests with the seed-derived Base key. The recovered address is the caller's
 * identity to the service, and for `appId: zapp` it is also the only address USDC may settle to.
 */
class OnrampRequestSigner(
    private val key: EvmKey,
    private val appId: String = DEFAULT_APP_ID,
) {
    val address: Address get() = key.address

    fun sign(
        nonce: String,
        method: String,
        path: String,
        body: String,
    ): String {
        val message = canonicalMessage(nonce, method, path, body).encodeToByteArray()
        // The message is a variable-length string, so the EIP-191 header carries its BYTE length.
        // Reusing the fixed ":\n32" header of the UserOp signer recovers a different address and
        // the service answers 401 with nothing pointing at the cause.
        val prefixed =
            byteArrayOf(EIP191_BYTE) +
                "$EIP191_HEADER${message.size}".encodeToByteArray() +
                message
        val signature = key.signRecoverable(keccak256(prefixed))
        val encoded =
            signature.r.toByteArray().padLeftToWord() +
                signature.s.toByteArray().padLeftToWord() +
                byteArrayOf((signature.yParity + V_OFFSET).toByte())
        return HEX_PREFIX + encoded.toHex()
    }

    internal fun canonicalMessage(
        nonce: String,
        method: String,
        path: String,
        body: String,
    ): String =
        buildString {
            append(DOMAIN).append('\n')
            append("app:").append(appId).append('\n')
            append("nonce:").append(nonce).append('\n')
            append("method:").append(method.uppercase()).append('\n')
            append("path:").append(path.substringBefore('?')).append('\n')
            append("body:").append(sha256Hex(body))
        }

    companion object {
        const val DEFAULT_APP_ID = "zapp"

        private const val DOMAIN = "p2p-onramp-operator"
        private const val EIP191_HEADER = "Ethereum Signed Message:\n"
        private const val EIP191_BYTE: Byte = 0x19
        private const val V_OFFSET = 27
        private const val HEX_PREFIX = "0x"

        internal fun sha256Hex(value: String): String =
            CryptographyProvider.Default
                .get(SHA256)
                .hasher()
                .hashBlocking(value.encodeToByteArray())
                .toHex()
    }
}

/** Defers key derivation past DI construction; the seed only unlocks on the first signed call. */
fun interface OnrampSignerProvider {
    suspend fun signer(): OnrampRequestSigner
}

/**
 * Where USDC settles: the ERC-4337 smart account the signing key owns, never the signing key
 * itself. Every other Zapp flow — offramp, the Base balance, Pay Merchant — already spends from
 * that account, so settling to the EOA would strand a purchase at an address the rest of the app
 * cannot see.
 *
 * The service derives the same address from the signer through thirdweb's
 * `AccountFactory.getAddress(signer, "")` and requires an exact match, so this is verified rather
 * than trusted: sending the EOA fails closed with `RECIPIENT_NOT_ALLOWED`.
 *
 * A separate seam from [OnrampSignerProvider] because resolving it costs an `eth_call`, which
 * neither belongs on the signing path nor in a test that only needs a signature.
 */
fun interface OnrampRecipientProvider {
    suspend fun recipient(): Address
}
