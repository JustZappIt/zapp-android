// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendCost
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendOutcome
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendRequest
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.basisPoints
import co.electriccoin.zcash.ui.common.privateusd.exactTokenAmount
import co.electriccoin.zcash.ui.common.privateusd.toBaseUnitsExact
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.zapp.ellipsizeAddress
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByQuantity
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.peer.Bps
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunDestination
import java.math.BigInteger

internal data class PrivateUsdSendForm(
    val mode: PrivateUsdSendMode,
    val phase: PrivateUsdSendPhase = PrivateUsdSendPhase.FORM,
    /** Pinned from the first balance on, so a refresh can't change what's sent. */
    val token: PrivateUsdToken? = null,
    val amount: NumberTextFieldInnerState = NumberTextFieldInnerState(),
    val recipient: String = "",
    val cost: PrivateUsdSendCost? = null,
    val reviewedRequest: PrivateUsdSendRequest? = null,
    val isBusy: Boolean = false,
    val outcome: PrivateUsdSendOutcome? = null,
    val error: StringResource? = null,
) {
    /** The dollars there are to send, and the one this form is pinned to even once none of it is left. */
    fun assets(balance: PrivateUsdBalanceState): List<PrivateUsdAsset> {
        val pinned = reviewedRequest?.token ?: token
        return balance.balances
            ?.assets
            ?.filter { it.token.isDollar && (it.available.signum() > 0 || it.token == pinned) }
            .orEmpty()
    }

    /** [amount] in [token]'s base units; null while none is typed, or one finer than the token goes. */
    fun units(token: PrivateUsdToken): BigInteger? = amount.amount?.toBaseUnitsExact(token.decimals)

    /** Back to where it was, since nothing left the wallet. */
    fun notSent(): PrivateUsdSendForm =
        copy(
            phase = if (reviewedRequest == null) PrivateUsdSendPhase.FORM else PrivateUsdSendPhase.REVIEW,
            error = stringRes(R.string.private_usd_send_not_sent),
        )

    /** The send this form describes, once it's complete, valid and more than nothing. */
    fun request(asset: PrivateUsdAsset): PrivateUsdSendRequest? {
        val units = units(asset.token)?.takeIf { it.signum() > 0 && it <= asset.available }
        val to = destination(mode, recipient)
        return if (units != null && to != null) PrivateUsdSendRequest(asset.token, units, to) else null
    }

    fun canSend(
        request: PrivateUsdSendRequest?,
        balance: PrivateUsdBalanceState
    ): Boolean =
        request != null &&
            balance.balances?.assets?.any { it.token == request.token && this.request(it) == request } == true

    /** Why a reviewed send can't go any more: the balance fell below it. */
    fun reviewError(balance: PrivateUsdBalanceState): StringResource? =
        stringRes(R.string.private_usd_send_balance_dropped).takeIf {
            phase == PrivateUsdSendPhase.REVIEW && !isBusy && !canSend(reviewedRequest, balance)
        }

    /** What's wrong with the amount; a zero is still being typed, so it isn't flagged. */
    fun amountError(asset: PrivateUsdAsset?): StringResource? {
        if (asset == null || amount.amount == null) return null
        val units = units(asset.token)
        val decimals = asset.token.decimals
        return when {
            units == null -> stringResByQuantity(R.plurals.private_usd_send_too_precise, decimals)
            units > asset.available -> stringRes(R.string.private_usd_send_too_much)
            else -> null
        }
    }

    fun recipientError(): StringResource? {
        val input = recipient.trim()
        return when {
            input.isEmpty() || destination(mode, input) != null -> {
                null
            }

            mode == PrivateUsdSendMode.WITHDRAW && publicAddress(input) == Address.ZERO -> {
                stringRes(R.string.private_usd_send_zero_address)
            }

            mode == PrivateUsdSendMode.WITHDRAW -> {
                stringRes(R.string.private_usd_send_invalid_0x)
            }

            else -> {
                stringRes(R.string.private_usd_send_invalid_0zk)
            }
        }
    }

    fun review(request: PrivateUsdSendRequest): PrivateUsdSendReviewState? =
        cost?.let {
            PrivateUsdSendReviewState(
                token = request.token.symbol,
                amount = exactTokenAmount(request.amount, request.token),
                railgunFee =
                    it.railgunFee.takeIf { fee -> fee.signum() > 0 }?.let { fee ->
                        PrivateUsdSendFee(
                            label =
                                stringRes(
                                    R.string.private_usd_send_review_railgun_fee,
                                    basisPoints(Bps(it.feeBasisPoints)),
                                ),
                            amount = exactTokenAmount(fee, request.token),
                        )
                    },
                receives = exactTokenAmount(it.received(request.amount), request.token),
                to = request.to.text,
            )
        }

    fun done(
        explorerTxUrl: String?,
        onOpenUrl: (String) -> Unit
    ): PrivateUsdSendDoneState? {
        val request = reviewedRequest
        val txHash =
            when (outcome) {
                is PrivateUsdSendOutcome.Sent -> outcome.txHash
                is PrivateUsdSendOutcome.Unconfirmed -> outcome.txHash
                PrivateUsdSendOutcome.NotSent, PrivateUsdSendOutcome.Busy, null -> null
            }
        if (request == null || txHash == null) return null
        return PrivateUsdSendDoneState(
            body =
                stringRes(
                    R.string.private_usd_send_done_body,
                    exactTokenAmount(request.amount, request.token),
                    request.to.text.ellipsizeAddress(),
                ),
            onViewTransaction = explorerTxUrl?.let { url -> { onOpenUrl(url + txHash.hex) } },
            note =
                stringRes(R.string.private_usd_send_unconfirmed)
                    .takeIf { outcome is PrivateUsdSendOutcome.Unconfirmed },
        )
    }

    private companion object {
        val HEX_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

        fun destination(
            mode: PrivateUsdSendMode,
            input: String
        ): RailgunDestination? {
            val recipient = input.trim()
            return when (mode) {
                PrivateUsdSendMode.PRIVATE -> {
                    RailgunAddress.parseOrNull(recipient)?.let(RailgunDestination::Private)
                }

                PrivateUsdSendMode.WITHDRAW -> {
                    publicAddress(recipient)?.takeIf { it != Address.ZERO }?.let(RailgunDestination::Public)
                }
            }
        }

        // A mixed-case address must carry its EIP-55 checksum, which catches a mistyped character.
        fun publicAddress(input: String): Address? {
            val hex = input.drop(2)
            val isSingleCase = hex == hex.lowercase() || hex == hex.uppercase()
            return Address
                .parseOrNull(input)
                ?.takeIf { HEX_ADDRESS.matches(input) && (isSingleCase || it.checksumHex == input) }
        }
    }
}
