// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendCost
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendMode
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendRequest
import co.electriccoin.zcash.ui.common.privateusd.toBaseUnits
import co.electriccoin.zcash.ui.common.privateusd.tokenAmount
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.evm.types.Address
import xyz.justzappit.railgun.RailgunSent
import java.math.BigInteger

internal data class PrivateUsdSendForm(
    val mode: PrivateUsdSendMode,
    val phase: PrivateUsdSendPhase = PrivateUsdSendPhase.FORM,
    val token: String? = null,
    val amount: NumberTextFieldInnerState = NumberTextFieldInnerState(),
    val recipient: String = "",
    val cost: PrivateUsdSendCost? = null,
    val sent: RailgunSent? = null,
    val error: StringResource? = null,
) {
    fun amountIn(asset: PrivateUsdAsset): BigInteger? = amount.amount?.toBaseUnits(asset.token.decimals)

    /** The send this form describes, once it's complete and valid. */
    fun request(asset: PrivateUsdAsset): PrivateUsdSendRequest? {
        val amount = amountIn(asset)?.takeIf { it.signum() > 0 && it <= asset.available }
        return if (amount != null && isValidRecipient(mode, recipient)) {
            PrivateUsdSendRequest(mode, asset.token, amount, recipient.trim())
        } else {
            null
        }
    }

    fun amountError(asset: PrivateUsdAsset?): StringResource? =
        asset
            ?.let { amountIn(it) }
            ?.takeIf { it > asset.available }
            ?.let { stringRes(R.string.private_usd_send_too_much) }

    fun recipientError(): StringResource? =
        if (recipient.isBlank() || isValidRecipient(mode, recipient)) {
            null
        } else if (mode == PrivateUsdSendMode.WITHDRAW) {
            stringRes(R.string.private_usd_send_invalid_0x)
        } else {
            stringRes(R.string.private_usd_send_invalid_0zk)
        }

    fun review(
        request: PrivateUsdSendRequest,
        paidByTestAccount: Boolean
    ): PrivateUsdSendReviewState? =
        cost?.let {
            PrivateUsdSendReviewState(
                amount = tokenAmount(request.amount, request.token),
                railgunFee =
                    it.railgunFee
                        .takeIf { fee -> fee.signum() > 0 }
                        ?.let { fee -> tokenAmount(fee, request.token) },
                networkFee =
                    it.broadcasterFee?.let { fee -> tokenAmount(fee, request.token) }
                        ?: stringRes(R.string.private_usd_send_review_test_account),
                receives = tokenAmount(it.received(request.amount), request.token),
                to = request.to,
                paidByTestAccount = paidByTestAccount,
            )
        }

    fun done(
        asset: PrivateUsdAsset,
        explorerTxUrl: String?
    ): PrivateUsdSendDoneState? =
        sent?.let {
            val to = recipient.trim()
            PrivateUsdSendDoneState(
                body =
                    stringRes(
                        R.string.private_usd_send_done_body,
                        tokenAmount(amountIn(asset) ?: BigInteger.ZERO, asset.token),
                        "${to.take(ADDRESS_HEAD)}…${to.takeLast(ADDRESS_TAIL)}",
                    ),
                explorerUrl = explorerTxUrl?.let { url -> url + it.txHash },
            )
        }

    private companion object {
        const val ADDRESS_HEAD = 10
        const val ADDRESS_TAIL = 6
        val ZERO_K_ADDRESS = Regex("^0zk1[02-9ac-hj-np-z]{90,}$")
        val HEX_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

        // A mixed-case address must carry its EIP-55 checksum, which catches a mistyped character.
        fun isValidRecipient(
            mode: PrivateUsdSendMode,
            input: String
        ): Boolean {
            val recipient = input.trim()
            val hex = recipient.drop(2)
            return when (mode) {
                PrivateUsdSendMode.PRIVATE -> {
                    ZERO_K_ADDRESS.matches(recipient)
                }

                PrivateUsdSendMode.WITHDRAW -> {
                    val isSingleCase = hex == hex.lowercase() || hex == hex.uppercase()
                    HEX_ADDRESS.matches(recipient) &&
                        (isSingleCase || Address.parseOrNull(recipient)?.checksumHex == recipient)
                }
            }
        }
    }
}
