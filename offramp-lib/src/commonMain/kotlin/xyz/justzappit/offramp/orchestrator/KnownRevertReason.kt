// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

/**
 * Curated subset of [KnownContractErrors] that the PAY offramp flow can surface with a localised,
 * user-actionable message. Add a variant here only if the UI knows how to explain it; otherwise
 * let the orchestrator fall through to the raw SDK error name from [KnownContractErrors.nameFor].
 *
 * Names match the canonical p2p.me SDK constants (`p2pdotme-sdk/src/contracts/errors.ts`) for
 * cross-referencing. The UI layer maps each variant to a `R.string.*` resource; this module stays
 * free of English copy. (The long-tail [KnownContractErrors.messageFor] carries SDK copy for the
 * uncurated errors — that is a deliberate exception, scoped to non-PAY-flow diagnostics.)
 */
enum class KnownRevertReason {
    /**
     * `placeOrder` reverts because the user's USDC tx limit (RP × multiplier) is below the
     * requested amount. Selector `0x91da284f`. New mainnet users hit this until they complete
     * social/KYC verification at app.p2p.me/limits.
     */
    BuyOrderAmountExceedsLimit,

    /** `placeOrder` reverts because the user has zero RP. Selector `0x412dd2b1`. */
    InsufficientReputation,

    /** Exceeds the global per-order cap (independent of RP). Selector `0xf42e41a1`. */
    OrderAmountExceedsLimit,

    /** Exceeds the user's daily fiat sell/pay limit. Selector `0xbba2edf9`. */
    SellAmountExceedsFiatLimit,

    /** The chosen currency isn't deployed on this network. Selector `0x02a6fdd2`. */
    CurrencyNotSupported,

    /** The account is blocklisted by the exchange. Selector `0xebb6f34b`. */
    UserIsBlacklisted,

    /** The exchange is paused (admin-triggered). Selector `0x4bbac5de`. */
    ExchangeNotOperational,

    /**
     * No merchant in the chosen circle has fiat liquidity for this order right now.
     * Selector `0x5d04ff4c`.
     */
    NotEnoughEligibleMerchants,

    /** The order timed out on-chain (merchant didn't act within the expiry window). Selector `0xc56873ba`. */
    OrderExpired,

    /**
     * `setSellOrderUpi` reverts because the UPI has already been sent for this order — i.e. a
     * resume path re-broadcast a tx that was already mined. Indicates a bug in our idempotency
     * tracking. Selector `0xc1654697`.
     */
    UpiAlreadySent,

    /**
     * The merchant SDK rejected our encrypted UPI handle. Strongly indicates a wire-format bug
     * in our ECIES port (compressed/uncompressed pubkey or HMAC scope mismatch). Selector
     * `0xaa60ec26`.
     */
    InvalidOrderUpi,

    /**
     * `setSellOrderUpi` was called before the merchant accepted the order. Indicates a race
     * where our polling missed the ACCEPTED → re-cancelled transition. Selector `0x6b1b90b4`.
     */
    OrderNotAccepted,

    /**
     * USDC `transferFrom` inside the order tx failed — typically allowance < amount or balance
     * < amount. The Diamond emits three different selectors depending on whether the ERC-20
     * returned `false`, reverted with `Error(string)`, or panicked.
     * Selectors `0x149f9fca` / `0x47bfece5` / `0x279bbc0c`.
     */
    UsdcTransferFailed,

    /**
     * `cancelOrder` reverts because the caller can't cancel the order in its current state. On this
     * contract a merely PLACED (unaccepted) order isn't user-cancellable — it auto-expires — and the
     * funds (never escrowed for an unaccepted PAY order) stay in the user's wallet. Selector
     * `0xea8e4eb5` (NOT_AUTHORIZED).
     */
    NotAuthorized,
}
