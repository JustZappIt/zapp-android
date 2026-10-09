// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** A Zcash transaction id as wallets show it: the 32 bytes reversed, in lowercase hex. */
@Serializable(with = ZcashTxId.Serializer::class)
@JvmInline
value class ZcashTxId private constructor(
    val hex: String
) {
    override fun toString() = hex

    companion object {
        fun parse(hex: String): ZcashTxId {
            require(hex.length == SWAP_WORD_BYTES * 2 && hex.isHex()) { "not a Zcash transaction id" }
            return ZcashTxId(hex.lowercase())
        }
    }

    internal object Serializer : HexSerializer<ZcashTxId>("ZcashTxId", ::parse, ZcashTxId::hex)
}

/** A transaction the wallet created for a swap, kept so it can be sent again until it is mined. */
@Serializable
data class ZcashTransaction(
    val txId: ZcashTxId,
    /** The transaction's bytes, in hex. */
    val raw: String,
    val expiryHeight: Long,
) {
    override fun toString() = "ZcashTransaction(txId=${txId.hex})"
}

sealed interface ZcashTransactionStatus {
    /** The wallet holds no such transaction, and hasn't scanned past its expiry height. */
    data object Unknown : ZcashTransactionStatus

    data object Unmined : ZcashTransactionStatus

    /** Unmined in every block up to its expiry height, so it never can be. */
    data object Expired : ZcashTransactionStatus

    data class Mined(
        val confirmations: Long
    ) : ZcashTransactionStatus
}

/** One look at a kept transaction: rebuilt once proven expired, kept before it's first sent, sent again until mined. */
internal suspend fun <T : Any> keepSending(
    kept: T?,
    status: suspend (T) -> ZcashTransactionStatus,
    build: suspend () -> T?,
    keep: suspend (T?) -> Unit,
    send: suspend (T) -> Unit,
): Pair<T, ZcashTransactionStatus>? {
    var transaction = kept
    var observed = transaction?.let { status(it) }
    if (observed == ZcashTransactionStatus.Expired) {
        transaction = null
        keep(null)
    }
    if (transaction == null) {
        transaction = build() ?: return null
        keep(transaction)
        observed = status(transaction)
    }
    val current = checkNotNull(observed)
    if (current !is ZcashTransactionStatus.Mined) send(transaction)
    return transaction to current
}
