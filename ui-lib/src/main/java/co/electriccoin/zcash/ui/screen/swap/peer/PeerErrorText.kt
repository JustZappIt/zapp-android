package co.electriccoin.zcash.ui.screen.swap.peer

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.offramp.peer.EscrowRevertBucket
import xyz.justzappit.offramp.peer.PeerError
import xyz.justzappit.offramp.peer.PeerErrorCode

/**
 * Maps the error to localized copy. Peer's own `message` and `remediation` strings are
 * developer-facing — they reference SDK calls and Basescan — so they stay in logs and bug reports
 * and never reach a user.
 *
 * A decoded escrow revert is more specific than the code that carried it: a `removeFunds` that
 * reverted because a buyer holds the balance is not "we could not create your order".
 */
internal fun PeerError.userMessage(): StringResource =
    (escrowRevert?.userFacing?.asCode() ?: code).userMessage()

private fun EscrowRevertBucket.asCode(): PeerErrorCode =
    when (this) {
        EscrowRevertBucket.INSUFFICIENT_AVAILABLE_FUNDS -> PeerErrorCode.INSUFFICIENT_AVAILABLE_FUNDS

        // A deposit id the chain does not know is a stale read, not a failed order.
        EscrowRevertBucket.STALE_DEPOSIT_ID -> PeerErrorCode.ORDER_NOT_FOUND

        EscrowRevertBucket.RAIL_UNAVAILABLE -> PeerErrorCode.UNSUPPORTED_PLATFORM

        EscrowRevertBucket.CURRENCY_UNAVAILABLE -> PeerErrorCode.UNSUPPORTED_PLATFORM_CURRENCY
    }

/**
 * Split in two so each half stays readable: the codes a user can act on inline, and everything
 * else, which shares the state-of-the-order card.
 */
internal fun PeerErrorCode.userMessage(): StringResource = inlineMessage() ?: terminalMessage()

/** Codes a user can act on where they stand: a field to fix, an amount to change. */
private fun PeerErrorCode.inlineMessage(): StringResource? =
    when (this) {
        PeerErrorCode.PAYEE_REGISTRATION_FAILED -> stringRes(R.string.peer_offramp_error_payee_unconfirmed)
        PeerErrorCode.CURATOR_UNAVAILABLE -> stringRes(R.string.peer_offramp_error_payee_unconfirmed)
        PeerErrorCode.PAYEE_NOT_FOUND_ON_PLATFORM -> stringRes(R.string.peer_offramp_error_payee_not_found)
        PeerErrorCode.INSUFFICIENT_TOKEN_BALANCE -> stringRes(R.string.peer_offramp_error_insufficient_balance)
        PeerErrorCode.INSUFFICIENT_AVAILABLE_FUNDS -> stringRes(R.string.peer_offramp_error_insufficient_available)
        PeerErrorCode.ACTIVE_INTENT_BLOCKS_WITHDRAWAL -> stringRes(R.string.peer_offramp_error_buyer_mid_payment)
        PeerErrorCode.NOTHING_TO_WITHDRAW -> stringRes(R.string.peer_offramp_error_nothing_to_withdraw)
        else -> null
    }

/** Everything else, where the useful information is what the order's state now is. */
private fun PeerErrorCode.terminalMessage(): StringResource =
    when (this) {
        PeerErrorCode.FUNDING_BRIDGE_FAILED -> stringRes(R.string.peer_offramp_error_bridge)

        // The unknown-outcome codes share one card. What matters is that it offers no retry.
        PeerErrorCode.TRANSACTION_SUBMISSION_UNKNOWN,
        PeerErrorCode.TRANSACTION_STATUS_UNKNOWN,
        PeerErrorCode.DEPOSIT_RESOLUTION_FAILED,
        -> stringRes(R.string.peer_offramp_error_checking)

        // Read failures never render as order failures; they reach here only if something else does.
        PeerErrorCode.INDEXER_UNAVAILABLE,
        PeerErrorCode.ORDER_NOT_FOUND,
        -> stringRes(R.string.peer_offramp_error_read)

        PeerErrorCode.UNSUPPORTED_PLATFORM,
        PeerErrorCode.UNSUPPORTED_PLATFORM_CURRENCY,
        -> stringRes(R.string.peer_offramp_error_rail_unavailable)

        else -> stringRes(R.string.peer_offramp_error_generic)
    }
