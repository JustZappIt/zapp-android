// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import androidx.annotation.StringRes
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdActivityData
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdConversion
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.common.privateusd.tokenAmount
import co.electriccoin.zcash.ui.design.component.zapp.ellipsizeAddress
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.reverse.receivedZat
import co.electriccoin.zcash.ui.screen.privateusd.reverse.stageLabel
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.RailgunKeySource
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.offramp.atomicswap.ReverseSwapResult
import xyz.justzappit.offramp.atomicswap.ReverseSwapStatus
import java.math.BigInteger

/** What moved no money isn't a row. */
class PrivateUsdActivityMapper {
    internal fun createState(
        data: PrivateUsdActivityData,
        deployment: AtomicSwapDeployment,
        conversion: PrivateUsdConversion?,
        currency: LocalCurrency,
        onOpenConversion: (PrivateUsdConversion) -> Unit,
        onOpenUrl: (String) -> Unit,
    ): PrivateUsdActivityState? {
        val date = dateTime(data.timestamp)
        return when (data) {
            is PrivateUsdActivityData.ToUsd -> {
                val underWay =
                    (conversion as? PrivateUsdConversion.ToUsd)?.takeIf { it.swap.record?.index == data.record.index }
                toUsd(data.record, date, deployment, currency, underWay, onOpenConversion, onOpenUrl)
            }

            is PrivateUsdActivityData.ToZec -> {
                val underWay =
                    (conversion as? PrivateUsdConversion.ToZec)?.takeIf { it.record.index == data.record.index }
                toZec(data.record, date, deployment, currency, underWay, onOpenConversion, onOpenUrl)
            }

            is PrivateUsdActivityData.Sent -> {
                val record = data.record
                val status =
                    if (record.confirmed) {
                        stringRes(
                            R.string.private_usd_activity_to,
                            record.to.text.ellipsizeAddress(ADDRESS_HEAD, ADDRESS_TAIL),
                        )
                    } else {
                        stringRes(R.string.private_usd_activity_unconfirmed)
                    }
                outgoing(
                    key = "sent-${record.txHash}",
                    withdraw = record.withdraw,
                    token = dollarToken(deployment, record.token),
                    amount = record.amount,
                    detail = joinDetail(date, status),
                    currency = currency,
                    onClick = { onOpenUrl(deployment.explorerTxUrl + record.txHash.hex) },
                )
            }

            is PrivateUsdActivityData.Proving -> {
                val send = data.send
                outgoing(
                    key = "proving-${send.id}",
                    withdraw = send.withdraw,
                    token = dollarToken(deployment, send.token),
                    amount = send.amount,
                    detail = joinDetail(date, stringRes(R.string.private_usd_activity_sending)),
                    currency = currency,
                    onClick = null,
                )
            }
        }
    }

    private fun toUsd(
        record: AtomicSwapRecord,
        date: StringResource,
        deployment: AtomicSwapDeployment,
        currency: LocalCurrency,
        underWay: PrivateUsdConversion.ToUsd?,
        onOpenConversion: (PrivateUsdConversion) -> Unit,
        onOpenUrl: (String) -> Unit,
    ): PrivateUsdActivityState? {
        val receives = record.receives ?: record.quote.amount
        val received = moved(dollarToken(deployment, record.quote.token), receives.micros)
        val converting =
            PrivateUsdActivityState(
                key = "to-usd-${record.index}",
                title = stringRes(R.string.private_usd_activity_converting),
                detail = underWay?.let { it.swap.stageDetail(it.confirmationsNeeded) } ?: date,
                amount = received?.signed(R.string.private_usd_activity_in),
                local = received?.local(currency),
                tone = PrivateUsdActivityTone.NEUTRAL,
                onClick = underWay?.let { { onOpenConversion(it) } },
                onClickLabel = underWay?.let { stringRes(R.string.private_usd_activity_open_conversion) },
            )
        return when (record.outcome) {
            null -> {
                converting
            }

            AtomicSwapOutcome.Paid -> {
                val paid = stringRes(Zatoshi(record.quote.depositZat)).asPrivacySensitive()
                converting.copy(
                    title = stringRes(R.string.private_usd_activity_converted),
                    detail = joinDetail(date, paid).inPreviousWallet(record.railgunKeys),
                    tone = PrivateUsdActivityTone.IN,
                    onClick = record.payoutTx?.let { tx -> { onOpenUrl(deployment.explorerTxUrl + tx.hex) } },
                    onClickLabel = record.payoutTx?.let { stringRes(R.string.private_usd_activity_open_transaction) },
                )
            }

            is AtomicSwapOutcome.Refunded -> {
                converting.copy(
                    title = stringRes(R.string.private_usd_activity_refunded),
                    detail = joinDetail(date, stringRes(R.string.private_usd_activity_refunded_detail)),
                    amount = null,
                    local = null,
                    onClick = null,
                    onClickLabel = null,
                )
            }

            is AtomicSwapOutcome.NothingSent -> {
                null
            }
        }
    }

    private fun toZec(
        record: ReverseSwapRecord,
        date: StringResource,
        deployment: AtomicSwapDeployment,
        currency: LocalCurrency,
        underWay: PrivateUsdConversion.ToZec?,
        onOpenConversion: (PrivateUsdConversion) -> Unit,
        onOpenUrl: (String) -> Unit,
    ): PrivateUsdActivityState? {
        val paid = moved(dollarToken(deployment, record.quote.terms.token), record.debit.micros)
        val funding = record.funding?.let { deployment.explorerTxUrl + it.txId.hex }
        val converting =
            PrivateUsdActivityState(
                key = "to-zec-${record.index}",
                title = stringRes(R.string.private_usd_converting_zec),
                detail = underWay?.let { stringRes(it.record.phase.stageLabel()) } ?: date,
                amount = paid?.signed(R.string.private_usd_activity_out),
                local = paid?.local(currency),
                tone = PrivateUsdActivityTone.NEUTRAL,
                onClick = underWay?.let { { onOpenConversion(it) } },
                onClickLabel = underWay?.let { stringRes(R.string.private_usd_activity_open_conversion) },
            )
        return when (val status = record.status) {
            ReverseSwapStatus.Previewed -> {
                null
            }

            is ReverseSwapStatus.UnderWay -> {
                converting
            }

            is ReverseSwapStatus.Over -> {
                when (status.result) {
                    ReverseSwapResult.RECEIVED -> {
                        converting.copy(
                            title = stringRes(R.string.private_usd_activity_converted_zec),
                            detail = joinDetail(date, stringRes(Zatoshi(record.receivedZat())).asPrivacySensitive()),
                            tone = PrivateUsdActivityTone.OUT,
                            onClick = funding?.let { url -> { onOpenUrl(url) } },
                            onClickLabel = funding?.let { stringRes(R.string.private_usd_activity_open_transaction) },
                        )
                    }

                    ReverseSwapResult.REFUNDED -> {
                        converting.copy(
                            title = stringRes(R.string.private_usd_activity_refunded),
                            detail =
                                joinDetail(date, stringRes(R.string.private_usd_activity_refunded_zec_detail))
                                    .inPreviousWallet(record.railgunKeys),
                            amount = null,
                            local = null,
                            onClick = null,
                            onClickLabel = null,
                        )
                    }

                    ReverseSwapResult.CANCELLED -> {
                        null
                    }
                }
            }
        }
    }

    // Null for a token this build doesn't know as a dollar, or an amount it can't read.
    private fun outgoing(
        key: String,
        withdraw: Boolean,
        token: PrivateUsdToken?,
        amount: BigInteger?,
        detail: StringResource,
        currency: LocalCurrency,
        onClick: (() -> Unit)?,
    ): PrivateUsdActivityState? =
        moved(token, amount)?.let { sent ->
            PrivateUsdActivityState(
                key = key,
                title =
                    stringRes(
                        if (withdraw) R.string.private_usd_activity_withdrew else R.string.private_usd_activity_sent
                    ),
                detail = detail,
                amount = sent.signed(R.string.private_usd_activity_out),
                local = sent.local(currency),
                tone = PrivateUsdActivityTone.OUT,
                onClick = onClick,
                onClickLabel = onClick?.let { stringRes(R.string.private_usd_activity_open_transaction) },
            )
        }

    // Paid into the Railgun wallet of the Zcash seed itself, which only Railway opens now.
    private fun StringResource.inPreviousWallet(railgunKeys: RailgunKeySource): StringResource =
        when (railgunKeys) {
            RailgunKeySource.ZCASH_SEED -> joinDetail(this, stringRes(R.string.private_usd_activity_previous_wallet))
            RailgunKeySource.BIP85 -> this
        }

    // Each record in its own token: a conversion kept from an earlier deployment may not be in today's.
    private fun dollarToken(
        deployment: AtomicSwapDeployment,
        address: Address
    ): PrivateUsdToken? = PrivateUsdTokens.find(deployment.railgunNetwork, address)?.takeIf { it.isDollar }

    private fun moved(
        token: PrivateUsdToken?,
        units: BigInteger?
    ): Moved? {
        val amount = units?.takeIf { it.signum() >= 0 }
        return if (token != null && amount != null) Moved(amount, token) else null
    }

    private class Moved(
        val amount: BigInteger,
        val token: PrivateUsdToken,
    ) {
        fun signed(
            @StringRes sign: Int
        ): StringResource = stringRes(sign, tokenAmount(amount, token)).asPrivacySensitive()

        fun local(currency: LocalCurrency): StringResource? =
            currency
                .takeUnless { it.isDollar }
                ?.format(amount.toDecimal(token.decimals))
                ?.asPrivacySensitive()
    }

    private companion object {
        const val ADDRESS_HEAD = 8
        const val ADDRESS_TAIL = 4
    }
}
