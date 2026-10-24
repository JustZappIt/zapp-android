// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.NoteCommitment
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.time.Duration

@Serializable
enum class DepositKind {
    /** An address's balance, shielded on its EIP-3009 authorization. */
    @SerialName("shield")
    SHIELD,

    /** What Railgun's screening sent back to an address's vault, shielded again. */
    @SerialName("rescue")
    RESCUE,
}

@Serializable
enum class DepositState {
    /** Kept, with no transaction named for it: posted, and posted again, as the same bytes. */
    @SerialName("signed")
    SIGNED,

    /** The relayer named transactions for it, or for another deposit of the address: the last is followed. */
    @SerialName("sent")
    SENT,

    /** Mined, and believed: the pinned contract's event names the address and one of this wallet's notes. */
    @SerialName("landed")
    LANDED,

    /** Refused (a shield twice, a rescue once): nothing was sent. */
    @SerialName("refused")
    REFUSED,

    /** Its transaction reverted, so nothing moved; the relayer holds the address back for an hour. */
    @SerialName("reverted")
    REVERTED,

    /** Signed again in its place, or another deposit of the address took its funds: it can no longer land. */
    @SerialName("replaced")
    REPLACED,

    /** Its authorization ran out before anything landed it. */
    @SerialName("expired")
    EXPIRED,
    ;

    val isFinished: Boolean get() = this != SIGNED && this != SENT
}

/** The terms a request was signed under: a refusal's pause holds while they stand, and the fee's until its expiry. */
@Serializable
data class SignedDepositTerms(
    val fee: Usdc6,
    val feeBps: Int,
    val minValue: Usdc6,
    val maxValidity: Long,
    val feeExpiresAt: Long? = null,
) {
    /** The same terms as [terms], the fee's expiry aside. */
    fun isSame(terms: DepositTerms): Boolean =
        fee.micros == terms.fee &&
            feeBps == terms.feeBps &&
            minValue.micros == terms.minValue &&
            maxValidity == terms.maxValidity

    companion object {
        fun of(terms: DepositTerms) =
            SignedDepositTerms(
                fee = Usdc6(terms.fee),
                feeBps = terms.feeBps,
                minValue = Usdc6(terms.minValue),
                maxValidity = terms.maxValidity,
                feeExpiresAt = terms.feeExpiresAt,
            )
    }
}

/** What the pinned contract recorded of a deposit or rescue that landed. */
@Serializable
data class DepositLanding(
    /** None when only its used authorization proves it: the relayer named no transaction. */
    val transaction: TxHash? = null,
    /** The event's `value`: what the contract shielded, before Railgun's fee. */
    val shielded: Usdc6,
    val fee: Usdc6,
    /** Unix seconds, on this device's clock, when it was seen landed. */
    val at: Long,
)

/**
 * One signed deposit or rescue, and where it stands: kept before it's posted, one per signing, so a request signed
 * again in its place stays, and its note is still known if it lands after all. It holds a signature and the wallet's
 * note: never show or log one, so [toString] tells only what it is and where it stands.
 */
@Serializable
data class DepositRecord(
    /** `shield-<index>-<validBefore>` or `rescue-<index>-<nonce>-<deadline>`. */
    val id: String,
    val kind: DepositKind,
    /** The deposit address's index. */
    val index: Int,
    val state: DepositState,
    /** Unix seconds when it was signed and kept. */
    val createdAt: Long,
    /** What it moves: a shield's authorized value, or a rescue's vault balance when it was signed. */
    val value: Usdc6,
    val terms: SignedDepositTerms,
    val shield: ShieldDeposit? = null,
    val rescue: RescueDeposit? = null,
    /** A shield's EIP-3009 nonce, `0x` hex: once it's used on the token, the shield landed. */
    val nonce: String? = null,
    /** Signed again after a refusal: refused again, it's dropped. */
    val afterRefusal: Boolean = false,
    /** Unix seconds of the last post. */
    val postedAt: Long? = null,
    /** Unix seconds before which it's not posted again. */
    val retryAt: Long? = null,
    /** The wait before the next post nothing answers, in seconds. */
    val backoff: Long = 0,
    /** The relayer asked to wait longer than a post waits, or couldn't be reached: the money waits at the address. */
    val waiting: Boolean = false,
    /** The transactions the relayer named, in order. */
    val transactions: List<TxHash> = emptyList(),
    /** They came with a `409`: another deposit of the address, believed only for one of this wallet's notes. */
    val conflict: Boolean = false,
    val landing: DepositLanding? = null,
    /** Unix seconds when it ended. */
    val endedAt: Long? = null,
) {
    init {
        require((kind == DepositKind.SHIELD) == (shield != null && rescue == null)) { "a shield keeps its request" }
        require((kind == DepositKind.RESCUE) == (rescue != null && shield == null)) { "a rescue keeps its request" }
    }

    /** The deposit address. */
    val from: Address get() = shield?.from ?: checkNotNull(rescue).from

    /** What the relayer keeps. */
    val fee: BigInteger get() = (shield?.fee ?: checkNotNull(rescue).fee).micros

    /** The note's commitment, as the contract's events name it. */
    val commitment: NoteCommitment
        get() = NoteCommitment.of((shield?.note ?: checkNotNull(rescue).note).depositNote().commitment)

    /** The first second it can no longer land: a shield's `validBefore`, or the second after a rescue's deadline. */
    val expiresAt: Long get() = shield?.validBefore ?: (checkNotNull(rescue).deadline + 1)

    /** What reaches the shielded balance: the event's once it landed, else what the request should bring. */
    val arrives: BigInteger
        get() =
            landing?.let { DepositFees.afterRailgun(it.shielded.micros) }
                ?: DepositFees.arrives(value.micros, fee)

    override fun toString(): String = "DepositRecord(${kind.name} of address $index, ${state.name})"

    internal fun ended(
        state: DepositState,
        now: Long,
    ) = copy(state = state, endedAt = now, retryAt = null, waiting = false)

    /** The relayer named [transactions] for it; with a `409` when [conflict]. */
    internal fun named(
        transactions: List<TxHash>,
        conflict: Boolean,
        now: Long,
    ) = copy(
        state = DepositState.SENT,
        transactions = transactions,
        conflict = conflict,
        postedAt = now,
        retryAt = null,
        backoff = 0,
        waiting = false,
    )

    /** Posted with no answer that names anything: the same bytes go again with backoff, never sooner than [after]. */
    internal fun unanswered(
        after: Duration?,
        waiting: Boolean,
        now: Long,
    ): DepositRecord {
        val backoff = if (backoff == 0L) BACKOFF_SECONDS else minOf(backoff * 2, LONGEST_WAIT_SECONDS)
        val wait = maxOf(backoff, after?.inWholeSeconds ?: 0)
        return copy(
            state = DepositState.SIGNED,
            backoff = backoff,
            postedAt = now,
            retryAt = now + wait,
            waiting = waiting,
        )
    }

    /** Posted again while named: still named, whatever the answer, so never signed again while that may land. */
    internal fun askedAgain(now: Long) = copy(postedAt = now)

    internal fun landed(
        landing: DepositLanding,
        now: Long,
    ) = copy(landing = landing).ended(DepositState.LANDED, now)
}

/**
 * The deposit addresses as a scan last found them: the one to show, and each one used. The scan reads [windows]
 * windows of [GAP], until one holds none used.
 */
@Serializable
data class DepositScan(
    /** Unix seconds. */
    val at: Long,
    /** The lowest index never shielded from: one holding less than the minimum stays shown. */
    val showing: Int,
    val used: List<ScannedAddress> = emptyList(),
    val windows: Int = 1,
) {
    fun balance(index: Int): BigInteger? = used.firstOrNull { it.index == index }?.balance?.micros
}

/** A used deposit address: it holds the token, or has deposited. */
@Serializable
data class ScannedAddress(
    val index: Int,
    val balance: Usdc6,
    val deposited: Boolean = false,
)

/** The first wait between posts nothing answered, doubling to [LONGEST_WAIT_SECONDS]. */
internal const val BACKOFF_SECONDS = 15L
internal const val LONGEST_WAIT_SECONDS = 5 * 60L

/** Addresses read at once; a scan stops after a window of this many with none used. */
const val GAP: Int = 20
