// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.privateusd.DollarRate
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendRecord
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.common.privateusd.local
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.common.privateusd.tokenAmount
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

internal enum class PrivateUsdActivityTone { IN, OUT, NEUTRAL }

internal data class PrivateUsdActivityState(
    val title: StringResource,
    val detail: StringResource,
    val amount: StringResource?,
    /** [amount] in the user's currency, while that isn't the dollar. */
    val local: StringResource?,
    val tone: PrivateUsdActivityTone,
    val onClick: (() -> Unit)?,
    /** The Ethereum transaction behind it, and its page on the explorer. */
    val txHash: String? = null,
    val txUrl: String? = null,
)

/**
 * Conversions and sends made on this device, newest first. Conversions that never sent anything are
 * left out: no money moved.
 */
internal class PrivateUsdActivity(
    private val deployment: AtomicSwapDeployment,
    private val onOpenConversion: () -> Unit,
    private val onOpenUrl: (String) -> Unit,
) {
    private val payoutToken =
        PrivateUsdTokens.find(deployment.railgunNetwork, deployment.config.token.checksumHex)

    fun of(
        swaps: List<AtomicSwapRecord>,
        sends: List<PrivateUsdSendRecord>,
        current: AtomicSwapState,
        rate: DollarRate?,
    ): List<PrivateUsdActivityState> {
        val conversions =
            swaps
                .filterNot { it.outcome is AtomicSwapOutcome.NothingSent }
                .map { (it.finishedAt ?: it.acceptedAt) to conversion(it, current, rate) }
        val payments = sends.mapNotNull { send -> payment(send, rate)?.let { send.sentAt to it } }
        return (conversions + payments).sortedByDescending { it.first }.map { it.second }
    }

    private fun conversion(
        record: AtomicSwapRecord,
        current: AtomicSwapState,
        rate: DollarRate?,
    ): PrivateUsdActivityState {
        val isCurrent = current.record?.index == record.index
        val received =
            payoutToken?.let { token ->
                Moved(record.receives?.let(::BigInteger) ?: BigInteger(record.quote.amount), token)
            }
        val date = date(record.finishedAt ?: record.acceptedAt)
        val onClick = onOpenConversion.takeIf { isCurrent }
        return when (record.outcome) {
            null -> {
                PrivateUsdActivityState(
                    title = stringRes(R.string.private_usd_activity_converting),
                    detail = if (isCurrent) current.stageDetail(deployment.makerConfirmations) else date,
                    amount = received?.signed(R.string.private_usd_activity_in, estimate = true),
                    local = received?.local(rate),
                    tone = PrivateUsdActivityTone.NEUTRAL,
                    onClick = onClick,
                )
            }

            is AtomicSwapOutcome.Refunded -> {
                PrivateUsdActivityState(
                    title = stringRes(R.string.private_usd_activity_refunded),
                    detail = detail(date, stringRes(R.string.private_usd_activity_refunded_detail)),
                    amount = null,
                    local = null,
                    tone = PrivateUsdActivityTone.NEUTRAL,
                    onClick = onClick,
                )
            }

            else -> {
                PrivateUsdActivityState(
                    title = stringRes(R.string.private_usd_activity_converted),
                    detail = detail(date, stringRes(Zatoshi(record.quote.depositZat)).asPrivacySensitive()),
                    amount = received?.signed(R.string.private_usd_activity_in, estimate = true),
                    local = received?.local(rate),
                    tone = PrivateUsdActivityTone.IN,
                    onClick = onClick,
                    txHash = record.payoutTx,
                    txUrl = record.payoutTx?.let { deployment.explorerTxUrl + it },
                )
            }
        }
    }

    private fun payment(
        send: PrivateUsdSendRecord,
        rate: DollarRate?,
    ): PrivateUsdActivityState? {
        val token = PrivateUsdTokens.find(deployment.railgunNetwork, send.token) ?: return null
        val sent = Moved(BigInteger(send.amount), token)
        val to = "${send.to.take(ADDRESS_HEAD)}…${send.to.takeLast(ADDRESS_TAIL)}"
        val txUrl = deployment.explorerTxUrl + send.txHash
        return PrivateUsdActivityState(
            title =
                stringRes(
                    if (send.withdraw) R.string.private_usd_activity_withdrew else R.string.private_usd_activity_sent
                ),
            detail = detail(date(send.sentAt), stringRes(R.string.private_usd_activity_to, to)),
            amount = sent.signed(R.string.private_usd_activity_out, estimate = false),
            local = sent.local(rate),
            tone = PrivateUsdActivityTone.OUT,
            onClick = { onOpenUrl(txUrl) },
            txHash = send.txHash,
            txUrl = txUrl,
        )
    }

    private fun detail(
        date: StringResource,
        extra: StringResource
    ) = stringRes(R.string.private_usd_activity_detail, date, extra)

    private fun date(epochSeconds: Long): StringResource =
        stringResByDateTime(
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault()),
            useFullFormat = false,
        )

    private class Moved(
        val amount: BigInteger,
        val token: PrivateUsdToken,
    ) {
        fun signed(
            sign: Int,
            estimate: Boolean
        ): StringResource = stringRes(sign, tokenAmount(amount, token, estimate)).asPrivacySensitive()

        fun local(rate: DollarRate?): StringResource? =
            rate?.takeIf { token.isDollar }?.local(amount.toDecimal(token.decimals))?.asPrivacySensitive()
    }

    private companion object {
        const val ADDRESS_HEAD = 8
        const val ADDRESS_TAIL = 4
    }
}
