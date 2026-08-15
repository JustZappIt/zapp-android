// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.abi.Selector4

/**
 * The EscrowV2 custom errors our four calls can produce. The selector is derived from the canonical
 * signature rather than carried beside it, so the two cannot drift apart; `EscrowRevertSelectorTest`
 * pins each derived value to the selector read off the deployed contract.
 *
 * [isBenign] marks a revert that means the chain is already in the state we asked for.
 * Everything with a null [userFacing] bucket means our calldata was malformed: nothing was
 * escrowed, and the useful action is a bug report rather than per-error copy.
 */
enum class EscrowRevert(
    val signature: String,
    val userFacing: EscrowRevertBucket? = null,
    val isBenign: Boolean = false,
) {
    AMOUNT_EXCEEDS_AVAILABLE(
        signature = "AmountExceedsAvailable(uint256,uint256)",
        userFacing = EscrowRevertBucket.INSUFFICIENT_AVAILABLE_FUNDS,
    ),
    DEPOSIT_NOT_FOUND(
        signature = "DepositNotFound(uint256)",
        userFacing = EscrowRevertBucket.STALE_DEPOSIT_ID,
    ),
    UNAUTHORIZED_CALLER_OR_DELEGATE(signature = "UnauthorizedCallerOrDelegate(address,address,address)"),
    UNAUTHORIZED_CALLER(signature = "UnauthorizedCaller(address,address)"),
    DEPOSIT_ALREADY_IN_STATE(
        signature = "DepositAlreadyInState(uint256,bool)",
        isBenign = true,
    ),
    PAYMENT_METHOD_NOT_WHITELISTED(
        signature = "PaymentMethodNotWhitelisted(bytes32)",
        userFacing = EscrowRevertBucket.RAIL_UNAVAILABLE,
    ),
    CURRENCY_NOT_SUPPORTED(
        signature = "CurrencyNotSupported(bytes32,bytes32)",
        userFacing = EscrowRevertBucket.CURRENCY_UNAVAILABLE,
    ),
    INVALID_ORACLE_ADAPTER(signature = "InvalidOracleAdapter(address)"),
    ARRAY_LENGTH_MISMATCH(signature = "ArrayLengthMismatch(uint256,uint256)"),
    EMPTY_PAYEE_DETAILS(signature = "EmptyPayeeDetails()"),
    ZERO_CONVERSION_RATE(signature = "ZeroConversionRate()"),
    CURRENCY_ALREADY_EXISTS(signature = "CurrencyAlreadyExists(bytes32,bytes32)"),
    PAYMENT_METHOD_ALREADY_EXISTS(signature = "PaymentMethodAlreadyExists(uint256,bytes32)"),
    INVALID_SPREAD(signature = "InvalidSpread(int16)"),
    ADAPTER_CONFIG_TOO_LONG(signature = "AdapterConfigTooLong(uint256,uint256)"),
    INVALID_RANGE(signature = "InvalidRange(uint256,uint256)"),
    ZERO_MIN_VALUE(signature = "ZeroMinValue()"),
    ZERO_VALUE(signature = "ZeroValue()"),
    ZERO_ADDRESS(signature = "ZeroAddress()"),
    ;

    val selector: Selector4 = Selector4.fromCanonicalSignature(signature)

    companion object {
        private val BY_SELECTOR: Map<Selector4, EscrowRevert> = entries.associateBy { it.selector }

        fun fromSelector(selector: Selector4?): EscrowRevert? = selector?.let(BY_SELECTOR::get)
    }
}

/** Reverts a user can act on. Everything else collapses to one generic card plus a report. */
enum class EscrowRevertBucket {
    INSUFFICIENT_AVAILABLE_FUNDS,
    STALE_DEPOSIT_ID,
    RAIL_UNAVAILABLE,
    CURRENCY_UNAVAILABLE,
}
