// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class AtomicSwapService { MAKER, RELAYER }

/** A maker's or relayer's failure: no answer, an answer that doesn't read, or a refusal. */
sealed class AtomicSwapHttpException(
    val service: AtomicSwapService,
    message: String,
    cause: Throwable?,
) : Exception(message, cause) {
    class Unreachable(
        service: AtomicSwapService,
        cause: Throwable,
    ) : AtomicSwapHttpException(service, "${service.label} is unreachable: ${cause.message}", cause)

    class Unreadable(
        service: AtomicSwapService,
        cause: Throwable,
    ) : AtomicSwapHttpException(service, "${service.label} sent an unreadable answer: ${cause.message}", cause)

    /** [code] is null when the service didn't send one this build knows. */
    class Refused(
        service: AtomicSwapService,
        val status: Int,
        val code: SwapErrorCode?,
        reason: String,
    ) : AtomicSwapHttpException(service, "${service.label} answered $status: $reason", null)
}

@Serializable
enum class SwapErrorCode {
    @SerialName("invalidRequest")
    INVALID_REQUEST,

    @SerialName("rejected")
    REJECTED,

    @SerialName("unknownQuote")
    UNKNOWN_QUOTE,

    @SerialName("unknownSwap")
    UNKNOWN_SWAP,

    @SerialName("unavailable")
    UNAVAILABLE,

    @SerialName("watchtowerUnavailable")
    WATCHTOWER_UNAVAILABLE,

    @SerialName("internal")
    INTERNAL,

    @SerialName("notFound")
    NOT_FOUND,

    @SerialName("methodNotAllowed")
    METHOD_NOT_ALLOWED,

    /** Spend a token from the issuer the `WWW-Authenticate` challenge names. */
    @SerialName("tokenRequired")
    TOKEN_REQUIRED;

    companion object {
        /** The code a service named, or null for one this build doesn't know. */
        fun named(name: String): SwapErrorCode? =
            entries.getOrNull(serializer().descriptor.getElementIndex(name))
    }
}

/** A step that can't go ahead now; running it again later may. */
class AtomicSwapBlockedException(
    val reason: AtomicSwapBlock,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

enum class AtomicSwapBlock {
    SWAP_UNDER_WAY,
    QUOTE_EXPIRED,
    WRONG_DEPLOYMENT,
    RELAYER_FEE,
    RAILGUN_CLOSED,

    /** Our claim lock lapses too soon to reveal under, or the maker has the turn after it. */
    CLAIM_LOCK_LAPSING,
    CHAIN_LAGGING,
    UNDER_WAY_ON_CHAIN,

    /** The chain's node sent an answer too short to read. */
    CHAIN_UNREADABLE,

    /** What the maker or the chain shows isn't what this swap agreed to. */
    MISMATCH,

    /** A deadline this step needs has passed, or leaves too little time. */
    DEADLINE_PASSED,

    /** The maker's ZEC isn't all spendable in the joint account yet. */
    DEPOSIT_UNCONFIRMED,

    /** Funding would now take more from the private balance than the review showed. */
    FUNDING_COST_CHANGED,

    /** This deployment has no supported gas sponsorship for new reverse funding. */
    FUNDING_UNAVAILABLE,

    /** The wallet can't pay the deposit in one transaction within what the user authorized. */
    DEPOSIT_UNPAYABLE,

    /** The Zcash wallet doesn't know the chain's height or can't sync now. */
    ZCASH_UNAVAILABLE,

    /** The Zcash network refused a kept transaction: it's sent again until it's mined or expires. */
    ZCASH_REJECTED,

    /** Every index looked at in one go was already used on a known contract; the next look goes on from there. */
    INDICES_IN_USE,

    /** The maker takes no more conversions for now: one started later may go ahead. */
    MAKER_BUSY,

    /** This device's tokens for the UTC day are spent, so no maker accepts another conversion before 00:00 UTC. */
    TOKENS_EXHAUSTED,

    /** The issuer refused this device, or the maker named a key, issuer or day this build takes no tokens for. */
    TOKENS_REFUSED,

    /** No token could be fetched now: the issuer is unreachable or its answer doesn't read. */
    TOKENS_UNAVAILABLE,
}

internal val AtomicSwapService.label get() = name.lowercase()
