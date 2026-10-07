// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:UseSerializers(LowercaseAddressSerializer::class)

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.jvm.JvmInline

/** `POST /v1/reverse/quote`'s answer: the swap's terms with the user's side, and the deadlines it runs to. */
@Serializable
data class ReverseQuote(
    val terms: SwapQuote,
    val user: Address,
    val refundNote: NoteCommitment,
    val fundingDeadline: Long,
    val readyDeadline: Long,
    val refundAfter: Long,
)

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

/** What funding a reverse swap takes from the private balance: the escrow and its fees. */
@Serializable
data class ReverseFundingCost(
    val debit: Usdc6,
    val railgunFee: Usdc6,
    val broadcasterFee: Usdc6?,
)

/** Funding kept before submission: proved calldata, or the signed transaction older builds prepared. */
@Serializable
data class ReverseFundingTransaction(
    val raw: String? = null,
    val txId: TxHash? = null,
    val cost: ReverseFundingCost,
    val request: ReverseFundingRequest? = null,
) {
    init {
        require((raw != null && txId != null && request == null) || (raw == null && request != null)) {
            "funding must retain its proved request or signed transaction"
        }
    }

    override fun toString() = "ReverseFundingTransaction(txId=$txId)"
}

@Serializable
data class ReverseReceiveEstimate(
    val availableZat: Long,
    val feeZat: Long,
) {
    val receivedZat: Long get() = availableZat - feeZat

    /** A sweep that pays a fee and still brings something home. */
    val isUsable: Boolean get() = feeZat > 0 && availableZat > feeZat
}

/** The joint account's sweep home, kept before it is first sent so it can be sent again unchanged. */
@Serializable
data class ReverseReceiveTransaction(
    val txId: ZcashTxId,
    val raw: String,
    val expiryHeight: Long,
    val receivedZat: Long,
    val feeZat: Long,
) {
    val transaction: ZcashTransaction get() = ZcashTransaction(txId, raw, expiryHeight)

    override fun toString() = "ReverseReceiveTransaction(txId=${txId.hex})"
}

/** The id of a swap's joint account in the wallet, as a record keeps it: the UUID's bytes in hex. */
@Serializable(with = JointAccountId.Serializer::class)
@JvmInline
value class JointAccountId private constructor(
    val hex: String
) {
    val bytes: ByteArray get() = hex.hexToBytes()

    override fun toString() = hex

    companion object {
        private const val UUID_BYTES = 16

        fun of(bytes: ByteArray): JointAccountId {
            require(bytes.size == UUID_BYTES) { "an account id is $UUID_BYTES bytes" }
            return JointAccountId(bytes.toHex())
        }

        fun parse(hex: String): JointAccountId {
            require(hex.isHex()) { "not an account id" }
            return of(hex.hexToBytes())
        }
    }

    internal object Serializer : HexSerializer<JointAccountId>("JointAccountId", ::parse, JointAccountId::hex)
}

@Serializable
data class ReverseSwapRecord(
    val index: Int,
    val deployment: SwapDeployment,
    val quote: ReverseQuote,
    val swapId: SwapId,
    val userShare: SwapShare,
    val acceptance: SwapAcceptance,
    val birthday: Long,
    /** Set once the joint account was imported for it. */
    val account: JointAccountId? = null,
    val phase: ReversePhase = ReversePhase.ACCEPTING,
    val cost: ReverseFundingCost? = null,
    val funding: ReverseFundingTransaction? = null,
    val ready: SwapAuthorization? = null,
    val cancelRequested: Boolean = false,
    val refundLock: SwapAuthorization? = null,
    val payout: SwapPayout? = null,
    val rescue: SwapRescue? = null,
    /** Only ever set by earlier builds, whose rescue retried in the background. */
    val rescuePending: Boolean = false,
    val receive: ReverseReceiveTransaction? = null,
    val receiveEstimate: ReverseReceiveEstimate? = null,
    val receiveConfirmations: Long = 0,
    val railgunKeys: RailgunKeySource = RailgunKeySource.ZCASH_SEED,
    /** Unix seconds on this device's clock when the user went ahead; null on records from before it was kept. */
    val acceptedAt: Long? = null,
) {
    val underWay: Boolean get() = phase != ReversePhase.QUOTED && !finished

    val finished: Boolean get() = phase in FINISHED

    /** Settlement was observed on Ethereum, even if preparing the ZEC transfer has not succeeded yet. */
    val isSettled: Boolean get() = phase == ReversePhase.RECEIVING || phase == ReversePhase.COMPLETE || receive != null

    val status: ReverseSwapStatus
        get() =
            when (phase) {
                ReversePhase.QUOTED -> {
                    ReverseSwapStatus.Previewed
                }

                ReversePhase.COMPLETE -> {
                    ReverseSwapStatus.Over(ReverseSwapResult.RECEIVED)
                }

                ReversePhase.REFUNDED -> {
                    ReverseSwapStatus.Over(ReverseSwapResult.REFUNDED)
                }

                ReversePhase.CANCELLED -> {
                    ReverseSwapStatus.Over(ReverseSwapResult.CANCELLED)
                }

                else -> {
                    ReverseSwapStatus.UnderWay(
                        phase,
                        awaiting(),
                        cancellable = !cancelRequested && !isSettled && phase !in REFUND_IN_PROGRESS,
                    )
                }
            }

    /** What paying for it takes from the private balance, or took. */
    val debit: Usdc6 get() = cost?.debit ?: quote.terms.amount

    private fun awaiting(): ReverseApproval? =
        when (phase) {
            ReversePhase.AWAITING_FUNDING -> ReverseApproval.FUNDING
            ReversePhase.AWAITING_READY -> ReverseApproval.SETTLEMENT
            else -> null
        }
}

/** A reverse swap as the app shows it, read from its phase and what it keeps. */
sealed interface ReverseSwapStatus {
    /** A quote the user only looked at: nothing is accepted or paid. */
    data object Previewed : ReverseSwapStatus

    /** [awaiting] is the approval only the user can give now, if any. */
    data class UnderWay(
        val phase: ReversePhase,
        val awaiting: ReverseApproval?,
        val cancellable: Boolean,
    ) : ReverseSwapStatus

    data class Over(
        val result: ReverseSwapResult
    ) : ReverseSwapStatus
}

/** What only the user can authorize, in the foreground. */
enum class ReverseApproval { FUNDING, SETTLEMENT }

enum class ReverseSwapResult { RECEIVED, REFUNDED, CANCELLED }

data class ReverseChainState(
    val swap: OnChainSwap?,
    val refundNote: NoteCommitment,
    val fundingBlock: Long,
    val block: Long,
    val now: Long,
    val lockDuration: Long,
)

internal fun ReverseQuote.requireWellFormed() {
    terms.requireWellFormed()
    require(fundingDeadline > 0 && fundingDeadline < readyDeadline && readyDeadline < refundAfter) { "bad deadlines" }
    require(refundAfter - fundingDeadline <= MAX_READY_WAIT) { "the deadlines are too far apart" }
}

internal const val MAX_READY_WAIT = 24 * 60 * 60L

private val FINISHED = setOf(ReversePhase.COMPLETE, ReversePhase.REFUNDED, ReversePhase.CANCELLED)
private val REFUND_IN_PROGRESS = setOf(ReversePhase.REFUNDING, ReversePhase.REFUND_PAYOUT)
