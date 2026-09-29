// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes

@Serializable
data class ReverseDeployment(
    val makerUrl: String,
    val relayerUrl: String,
    val rpcUrl: String,
    val chainId: Long,
    val contract: String,
    val token: String,
    val railgun: String,
    val maker: String,
    val relayer: String,
    val maxRefundFee: String,
    val confirmations: Long = DEFAULT_CONFIRMATIONS,
) {
    init {
        require(chainId > 0 && confirmations > 0)
        listOf(contract, token, railgun, maker, relayer).forEach { fixedHex(it, SWAP_ADDRESS_BYTES) }
        decimalUnits(maxRefundFee)
    }
}

@Serializable
data class ReverseQuote(
    val terms: SwapQuote,
    val user: String,
    val refundNote: String,
    val fundingDeadline: Long,
    val readyDeadline: Long,
    val refundAfter: Long,
) {
    init {
        fixedHex(user, SWAP_ADDRESS_BYTES)
        fixedHex(refundNote, SWAP_WORD_BYTES)
        fixedHex(terms.quoteId, SWAP_WORD_BYTES)
        fixedHex(terms.makerShare, SWAP_SHARE_BYTES)
        fixedHex(terms.makerProof, SWAP_SHARE_BYTES)
        listOf(terms.maker, terms.contract, terms.token).forEach { fixedHex(it, SWAP_ADDRESS_BYTES) }
        require(decimalUnits(terms.amount).signum() > 0)
        require(terms.depositZat in 1..MAX_ZATOSHI)
        require(fundingDeadline > 0 && fundingDeadline < readyDeadline && readyDeadline < refundAfter)
        require(refundAfter - fundingDeadline <= MAX_READY_WAIT)
    }
}

@Serializable
enum class ReversePhase {
    QUOTED,
    ACCEPTING,
    AWAITING_FUNDING,
    SENDING_USDC,
    CONFIRMING_ESCROW,
    RECEIVING_ZEC,
    AWAITING_READY,
    SETTLING,
    RECEIVING,
    REFUND_WAIT,
    REFUNDING,
    REFUND_PAYOUT,
    COMPLETE,
    REFUNDED,
    CANCELLED,
}

@Serializable
data class ReverseAuthorization(
    val swapId: String,
    val deadline: Long,
    val signature: String
) {
    init {
        fixedHex(swapId, SWAP_WORD_BYTES)
        fixedHex(signature, SWAP_SIGNATURE_BYTES)
        require(deadline > 0)
    }
}

@Serializable
data class ReverseFundingCost(
    val debit: String,
    val railgunFee: String,
    val broadcasterFee: String?
) {
    init {
        decimalUnits(debit)
        decimalUnits(railgunFee)
        broadcasterFee?.let { decimalUnits(it) }
    }
}

@Serializable
data class ReverseFundingTransaction(
    val raw: String,
    val txId: String,
    val cost: ReverseFundingCost
) {
    init {
        fixedHex(txId, SWAP_WORD_BYTES)
        require(raw.startsWith("0x") && raw.length > 2 && raw.length % 2 == 0)
        raw.hexToBytes()
    }

    override fun toString() = "ReverseFundingTransaction(txId=$txId)"
}

@Serializable
data class ReverseReceiveEstimate(
    val availableZat: Long,
    val feeZat: Long
) {
    init {
        require(feeZat > 0 && availableZat > feeZat)
    }

    val receivedZat: Long get() = availableZat - feeZat
}

@Serializable
data class ReverseReceiveTransaction(
    val txId: String,
    val raw: String,
    val expiryHeight: Long,
    val receivedZat: Long,
    val feeZat: Long,
) {
    init {
        fixedHex("0x$txId", SWAP_WORD_BYTES)
        require(raw.isNotEmpty() && raw.length % 2 == 0)
        require(raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        require(expiryHeight > 0 && receivedZat > 0 && feeZat > 0)
    }

    override fun toString() = "ReverseReceiveTransaction(txId=$txId)"
}

@Serializable
data class ReverseSwapRecord(
    val index: Int,
    val deployment: ReverseDeployment,
    val quote: ReverseQuote,
    val swapId: String,
    val userShare: String,
    val acceptance: ReverseAcceptance,
    val birthday: Long,
    val account: String? = null,
    val phase: ReversePhase = ReversePhase.ACCEPTING,
    val cost: ReverseFundingCost? = null,
    val funding: ReverseFundingTransaction? = null,
    val ready: ReverseAuthorization? = null,
    val cancelRequested: Boolean = false,
    val refundLock: ReverseAuthorization? = null,
    val payout: ReversePayout? = null,
    val rescuePending: Boolean = false,
    val receive: ReverseReceiveTransaction? = null,
    val receiveEstimate: ReverseReceiveEstimate? = null,
    val receiveConfirmations: Long = 0,
) {
    val underWay: Boolean get() = phase != ReversePhase.QUOTED && !finished

    val finished: Boolean get() = phase in setOf(ReversePhase.COMPLETE, ReversePhase.REFUNDED, ReversePhase.CANCELLED)
}

@Serializable
data class ReverseAcceptance(
    val userShare: String,
    val userProof: String,
    val viewingKeys: String
) {
    init {
        listOf(userShare, userProof, viewingKeys).forEach { fixedHex(it, SWAP_SHARE_BYTES) }
    }
}

@Serializable
data class ReverseNote(
    val npk: String,
    val encryptedBundle: List<String>,
    val shieldKey: String
) {
    init {
        require(encryptedBundle.size == CIPHERTEXT_WORDS)
        (encryptedBundle + npk + shieldKey).forEach { fixedHex(it, SWAP_WORD_BYTES) }
    }
}

@Serializable
data class ReversePayout(
    val swapId: String,
    val note: ReverseNote,
    val fee: String,
    val signature: String
) {
    init {
        fixedHex(swapId, SWAP_WORD_BYTES)
        fixedHex(signature, SWAP_SIGNATURE_BYTES)
        decimalUnits(fee)
    }
}

@Serializable
data class ReverseRefund(
    val swapId: String,
    val secret: String,
    val payout: ReversePayout
)

@Serializable
data class ReverseMakerInfo(
    val apiVersion: Int,
    val maker: String,
    val chainId: Long,
    val contract: String,
    val token: String,
    val zcashNetwork: String,
    val reverseEnabled: Boolean,
)

@Serializable
data class ReverseRelayerTerms(
    val relayer: String,
    val chainId: Long,
    val contract: String,
    val fee: String
)

data class ReverseChainState(
    val swap: OnChainSwap?,
    val refundNote: ByteArray,
    val fundingBlock: Long,
    val block: Long,
    val now: Long,
    val lockDuration: Long,
)

enum class ReverseTransactionStatus { UNKNOWN, PENDING, CONFIRMED, EXPIRED, REVERTED }

data class ReverseReceiveStatus(
    val status: ReverseTransactionStatus,
    val confirmations: Long = 0,
) {
    init {
        require(confirmations >= 0)
    }
}

fun fixedHex(value: String, bytes: Int): ByteArray {
    require(value.length == 2 + bytes * 2 && value.startsWith("0x")) { "invalid fixed-length hex" }
    require(value.drop(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
    return value.hexToBytes()
}

fun decimalUnits(value: String): BigInteger {
    require(value.isNotEmpty() && value.all { it in '0'..'9' }) { "invalid decimal amount" }
    return BigInteger(value).also { require(it.bitLength() <= SWAP_AMOUNT_BITS) }
}

internal fun sameAddress(left: String, right: String): Boolean =
    Address.fromBytes(fixedHex(left, SWAP_ADDRESS_BYTES)) == Address.fromBytes(fixedHex(right, SWAP_ADDRESS_BYTES))

internal const val MAX_READY_WAIT = 24 * 60 * 60L
private const val MAX_ZATOSHI = 2_100_000_000_000_000L

private const val DEFAULT_CONFIRMATIONS = 3L
private const val CIPHERTEXT_WORDS = 3
