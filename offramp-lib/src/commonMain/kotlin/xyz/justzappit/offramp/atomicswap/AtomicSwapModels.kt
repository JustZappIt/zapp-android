// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6

enum class SwapStage { OPEN, READY, CLAIMED, REFUNDED }

/** A swap on the chain: the terms this wallet expects, once the contract's hash of them matches, and its state. */
class OnChainSwap(
    val maker: Address,
    val t0: Long,
    val stage: SwapStage,
    /** A payout into Railgun has left the contract. */
    val paidOut: Boolean,
    val user: Address,
    val t1: Long,
    val token: Address,
    val claimLockUntil: Long,
    val amount: Usdc6,
    val refundLockUntil: Long,
    val makerShare: SwapShare,
    val userShare: SwapShare,
    /** The share a claim or refund revealed, once there has been one. */
    val secret: ByteArray,
    /** All zero for a reverse swap, whose refund note the contract keeps apart. */
    val payoutNote: NoteCommitment,
) {
    val terms: SwapTerms get() = SwapTerms(maker, token, amount, makerShare, userShare, user, t0, t1, payoutNote)
}

/** `getSwap(id)`: what the contract keeps of a swap besides its terms, which it holds only as [termsHash]. */
class SwapState(
    val termsHash: ByteArray,
    val stage: SwapStage,
    val paidOut: Boolean,
    val claimLockUntil: Long,
    val refundLockUntil: Long,
    val secret: ByteArray,
) {
    /** The swap [terms] describe, once they hash to [termsHash]: the chain shows nothing else of them. */
    fun verified(terms: SwapTerms): OnChainSwap {
        if (!termsHash.contentEquals(terms.hash())) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.MISMATCH, "the swap on chain opened with other terms")
        }
        return OnChainSwap(
            maker = terms.maker,
            t0 = terms.t0,
            stage = stage,
            paidOut = paidOut,
            user = terms.user,
            t1 = terms.t1,
            token = terms.token,
            claimLockUntil = claimLockUntil,
            amount = terms.amount,
            refundLockUntil = refundLockUntil,
            makerShare = terms.makerKey,
            userShare = terms.userKey,
            secret = secret,
            payoutNote = terms.payoutNote,
        )
    }
}

/** What the app keeps about a swap it accepted. Its keys derive from the seed and [index] again. */
@Serializable(with = AtomicSwapRecordSerializer::class)
data class AtomicSwapRecord(
    val index: Int,
    val quote: SwapQuote,
    val swapId: SwapId,
    /** The Zcash height read before depositing: a refund imports the deposit account from here. */
    val zcashHeight: Long,
    /** Unix seconds on this device's clock. */
    val acceptedAt: Long,
    /** Expected net payout, replaced by the confirmed event's net payout at completion. */
    val receives: Usdc6,
    /** The relayer's fee the offer named, which the payout pays while the relayer takes it, until [feeExpiresAt]. */
    val relayerFee: Usdc6,
    val deposit: SwapDeposit = SwapDeposit.NotStarted,
    /** A refund's sweep home, kept before it is first sent. */
    val sweep: ZcashTransaction? = null,
    val end: SwapEnd? = null,
    /** The Ethereum transaction that shielded the payout. */
    val payoutTx: TxHash? = null,
    val maxTotalZat: Long? = null,
    val payout: SwapPayout? = null,
    /** The deadlines the maker opened with, from its acceptance; with the quote and our keys, the swap's terms. */
    val t0: Long? = null,
    val t1: Long? = null,
    /** Unix seconds; none where the relayer quoted a fee it doesn't price by gas. */
    val feeExpiresAt: Long? = null,
) {
    val outcome: AtomicSwapOutcome? get() = end?.outcome

    val finished: Boolean get() = end != null
}

/** How far a swap's deposit got. It's marked started before it's created, so an interrupted one is never paid twice. */
sealed interface SwapDeposit {
    val txId: ZcashTxId? get() = null

    /** The bytes to send again until it's mined. */
    val transaction: ZcashTransaction? get() = null

    data object NotStarted : SwapDeposit

    /** Started, with no transaction known: an interruption may have cut it short. */
    data object Started : SwapDeposit

    data class Kept(
        override val transaction: ZcashTransaction
    ) : SwapDeposit {
        override val txId: ZcashTxId get() = transaction.txId
    }
}

/** How a swap ended, and when on this device's clock. */
data class SwapEnd(
    val outcome: AtomicSwapOutcome,
    val at: Long,
)

@Serializable
sealed interface AtomicSwapOutcome {
    @Serializable
    @SerialName("paid")
    data object Paid : AtomicSwapOutcome

    /** The deposit came home in [sweepTxId]. */
    @Serializable
    @SerialName("refunded")
    data class Refunded(
        val sweepTxId: ZcashTxId,
        val cause: RefundCause,
    ) : AtomicSwapOutcome

    /** Over before any ZEC left the wallet. */
    @Serializable
    @SerialName("nothing_sent")
    data class NothingSent(
        val cause: NothingSentCause
    ) : AtomicSwapOutcome
}

enum class RefundCause { MAKER_CANCELLED, NOT_CLAIMED_IN_TIME }

enum class NothingSentCause {
    QUOTE_EXPIRED,
    MAKER_REFUSED,
    MAKER_UNAVAILABLE,
    NEVER_OPENED,
    MISMATCH,
    DEPOSIT_WINDOW_MISSED,
    MAKER_CANCELLED,
}

sealed interface AtomicSwapStep {
    data class Waiting(
        val reason: AtomicSwapWait,
        val t0: Long? = null,
        val t1: Long? = null,
    ) : AtomicSwapStep

    data class Finished(
        val outcome: AtomicSwapOutcome
    ) : AtomicSwapStep
}

enum class AtomicSwapWait {
    OPENING,
    CONFIRMING,

    /** The maker called the swap off while the deposit was unmined: it comes home if it is mined. */
    DEPOSIT_UNSETTLED,

    /** The refunded deposit is on its way home. */
    REFUNDING,
}

enum class AtomicSwapActivity { DEPOSITING, CLAIMING, PAYING_OUT, SWEEPING }

/** A quote for swap [index], not accepted yet. [receives] reaches Railgun after the relayer's and Railgun's fees. */
data class AtomicSwapOffer(
    val index: Int,
    val requested: Usdc6,
    val quote: SwapQuote,
    val relayerFee: Usdc6,
    val receives: Usdc6,
    val maxTotalZat: Long? = null,
    /** Until when the relayer takes [relayerFee], in unix seconds. */
    val feeExpiresAt: Long? = null,
) {
    /** The relayer's fee leaves something of the amount: a swap it would take all of is never started. */
    val paysOut: Boolean get() = relayerFee < quote.amount
}

/** [AtomicSwapRecord] as the store keeps it: flat, with the deposit and the end as flags and nullables. */
internal object AtomicSwapRecordSerializer : KSerializer<AtomicSwapRecord> {
    override val descriptor: SerialDescriptor = Stored.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: AtomicSwapRecord
    ) = encoder.encodeSerializableValue(Stored.serializer(), Stored.of(value))

    override fun deserialize(decoder: Decoder): AtomicSwapRecord =
        decoder.decodeSerializableValue(Stored.serializer()).record()

    @Serializable
    private class Stored(
        val index: Int,
        val quote: SwapQuote,
        val swapId: SwapId,
        val zcashHeight: Long,
        val acceptedAt: Long,
        val receives: Usdc6,
        val relayerFee: Usdc6,
        val depositAttempted: Boolean = false,
        val outcome: AtomicSwapOutcome? = null,
        val finishedAt: Long? = null,
        val payoutTx: TxHash? = null,
        val maxTotalZat: Long? = null,
        val deposit: ZcashTransaction? = null,
        val sweep: ZcashTransaction? = null,
        val payout: SwapPayout? = null,
        val t0: Long? = null,
        val t1: Long? = null,
        val feeExpiresAt: Long? = null,
    ) {
        fun record() =
            AtomicSwapRecord(
                index = index,
                quote = quote,
                swapId = swapId,
                zcashHeight = zcashHeight,
                acceptedAt = acceptedAt,
                receives = receives,
                relayerFee = relayerFee,
                deposit =
                    when {
                        deposit != null -> SwapDeposit.Kept(deposit)
                        depositAttempted -> SwapDeposit.Started
                        else -> SwapDeposit.NotStarted
                    },
                sweep = sweep,
                end = outcome?.let { SwapEnd(it, requireNotNull(finishedAt)) },
                payoutTx = payoutTx,
                maxTotalZat = maxTotalZat,
                payout = payout,
                t0 = t0,
                t1 = t1,
                feeExpiresAt = feeExpiresAt,
            )

        companion object {
            fun of(record: AtomicSwapRecord) =
                Stored(
                    index = record.index,
                    quote = record.quote,
                    swapId = record.swapId,
                    zcashHeight = record.zcashHeight,
                    acceptedAt = record.acceptedAt,
                    receives = record.receives,
                    relayerFee = record.relayerFee,
                    depositAttempted = record.deposit != SwapDeposit.NotStarted,
                    outcome = record.end?.outcome,
                    finishedAt = record.end?.at,
                    payoutTx = record.payoutTx,
                    maxTotalZat = record.maxTotalZat,
                    deposit = record.deposit.transaction,
                    sweep = record.sweep,
                    payout = record.payout,
                    t0 = record.t0,
                    t1 = record.t1,
                    feeExpiresAt = record.feeExpiresAt,
                )
        }
    }
}
