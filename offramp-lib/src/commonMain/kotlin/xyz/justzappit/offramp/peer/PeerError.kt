// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.abi.Selector4
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash

/**
 * Peer's error vocabulary, narrowed to the codes this flow can actually raise. Names match theirs
 * where they overlap, so a bug report reads the same in their support channel as in our logs.
 *
 * [retryable] is a contract, not a hint: a false value must never be retried automatically.
 * [allowsManualRetry] is narrower still — the three unknown-outcome codes must not even offer the
 * user a retry button, because a second attempt is how one deposit becomes two.
 *
 * [nothingEscrowed] is the third contract, and the one the balance depends on: whether the failure
 * proves the USDC never left the account. Only a proven negative releases the amount an attempt had
 * reserved. Anything unproven keeps it reserved, because releasing it is how the same coins get
 * spent twice.
 */
enum class PeerErrorCode(
    val retryable: Boolean,
    val allowsManualRetry: Boolean = true,
    val nothingEscrowed: Boolean = true,
) {
    // Reached only by decoding an escrow revert; nothing rejects a rail up front.
    UNSUPPORTED_PLATFORM(retryable = false),
    UNSUPPORTED_PLATFORM_CURRENCY(retryable = false),
    PAYEE_REGISTRATION_FAILED(retryable = true),

    /** The curator looked the handle up on the platform and there is no such account. */
    PAYEE_NOT_FOUND_ON_PLATFORM(retryable = false),

    /**
     * The curator itself could not be reached. A different service from the indexer, and a bug
     * report that names the wrong one sends the reader to the wrong logs.
     */
    CURATOR_UNAVAILABLE(retryable = true),
    INSUFFICIENT_TOKEN_BALANCE(retryable = false),

    /** The send landed; only the deposit it opened could not be named. The USDC is escrowed. */
    DEPOSIT_RESOLUTION_FAILED(retryable = false, allowsManualRetry = false, nothingEscrowed = false),
    TRANSACTION_FAILED(retryable = false),
    TRANSACTION_SUBMISSION_UNKNOWN(retryable = false, allowsManualRetry = false, nothingEscrowed = false),
    TRANSACTION_STATUS_UNKNOWN(retryable = false, allowsManualRetry = false, nothingEscrowed = false),
    ORDER_NOT_FOUND(retryable = true),

    /** Raised while hunting for a deposit that may well exist, so it settles nothing either way. */
    INDEXER_UNAVAILABLE(retryable = true, nothingEscrowed = false),
    INVALID_DEPOSIT_ID(retryable = false),
    NOTHING_TO_WITHDRAW(retryable = false),
    ACTIVE_INTENT_BLOCKS_WITHDRAWAL(retryable = true),
    INSUFFICIENT_AVAILABLE_FUNDS(retryable = true),

    // Ours: Peer Cash never funds the account, its caller does.
    FUNDING_BRIDGE_FAILED(retryable = true),
}

/**
 * What the user can be pointed at when the app cannot tell whether money moved. Never a retry.
 */
sealed interface PeerRecovery {
    data class InspectBaseTransaction(
        val txHash: TxHash,
        val operation: String,
    ) : PeerRecovery

    data class InspectDepositor(
        val depositor: Address,
    ) : PeerRecovery
}

data class PeerError(
    val code: PeerErrorCode,
    val recovery: PeerRecovery? = null,
    val revertSelector: Selector4? = null,
    val escrowRevert: EscrowRevert? = null,
    val solidityErrorString: String? = null,
    val cause: Throwable? = null,
) {
    val retryable: Boolean get() = code.retryable

    val allowsManualRetry: Boolean get() = code.allowsManualRetry

    val nothingEscrowed: Boolean get() = code.nothingEscrowed
}

/** Carries a [PeerError] across suspend boundaries without flattening it to a message string. */
class PeerException(
    val error: PeerError,
) : Exception(error.code.name, error.cause)

fun PeerErrorCode.asError(
    recovery: PeerRecovery? = null,
    cause: Throwable? = null,
): PeerError = PeerError(code = this, recovery = recovery, cause = cause)

fun PeerErrorCode.asException(
    recovery: PeerRecovery? = null,
    cause: Throwable? = null,
): PeerException = PeerException(asError(recovery = recovery, cause = cause))
