// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendRecord
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
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
    ): List<PrivateUsdActivityState> {
        val conversions =
            swaps
                .filterNot { it.outcome is AtomicSwapOutcome.NothingSent }
                .map { (it.finishedAt ?: it.acceptedAt) to conversion(it, current) }
        val payments = sends.mapNotNull { send -> payment(send)?.let { send.sentAt to it } }
        return (conversions + payments).sortedByDescending { it.first }.map { it.second }
    }

    private fun conversion(
        record: AtomicSwapRecord,
        current: AtomicSwapState,
    ): PrivateUsdActivityState {
        val isCurrent = current.record?.index == record.index
        val received =
            payoutToken?.let { token ->
                val amount = record.receives?.let(::BigInteger) ?: BigInteger(record.quote.amount)
                stringRes(R.string.private_usd_activity_in, tokenAmount(amount, token, estimate = true))
                    .asPrivacySensitive()
            }
        val date = date(record.finishedAt ?: record.acceptedAt)
        return when (record.outcome) {
            null -> {
                PrivateUsdActivityState(
                    title = stringRes(R.string.private_usd_activity_converting),
                    detail = if (isCurrent) current.stageDetail(deployment.makerConfirmations) else date,
                    amount = received,
                    tone = PrivateUsdActivityTone.NEUTRAL,
                    onClick = onOpenConversion.takeIf { isCurrent },
                )
            }

            is AtomicSwapOutcome.Refunded -> {
                PrivateUsdActivityState(
                    title = stringRes(R.string.private_usd_activity_refunded),
                    detail = detail(date, stringRes(R.string.private_usd_activity_refunded_detail)),
                    amount = null,
                    tone = PrivateUsdActivityTone.NEUTRAL,
                    onClick = onOpenConversion.takeIf { isCurrent },
                )
            }

            else -> {
                PrivateUsdActivityState(
                    title = stringRes(R.string.private_usd_activity_converted),
                    detail = detail(date, stringRes(Zatoshi(record.quote.depositZat)).asPrivacySensitive()),
                    amount = received,
                    tone = PrivateUsdActivityTone.IN,
                    onClick = onOpenConversion.takeIf { isCurrent },
                    txHash = record.payoutTx,
                    txUrl = record.payoutTx?.let { deployment.explorerTxUrl + it },
                )
            }
        }
    }

    private fun payment(send: PrivateUsdSendRecord): PrivateUsdActivityState? {
        val token = PrivateUsdTokens.find(deployment.railgunNetwork, send.token) ?: return null
        val to = "${send.to.take(ADDRESS_HEAD)}…${send.to.takeLast(ADDRESS_TAIL)}"
        return PrivateUsdActivityState(
            title =
                stringRes(
                    if (send.withdraw) R.string.private_usd_activity_withdrew else R.string.private_usd_activity_sent
                ),
            detail = detail(date(send.sentAt), stringRes(R.string.private_usd_activity_to, to)),
            amount =
                stringRes(R.string.private_usd_activity_out, tokenAmount(BigInteger(send.amount), token))
                    .asPrivacySensitive(),
            tone = PrivateUsdActivityTone.OUT,
            onClick = { onOpenUrl(deployment.explorerTxUrl + send.txHash) },
            txHash = send.txHash,
            txUrl = deployment.explorerTxUrl + send.txHash,
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

    private companion object {
        const val ADDRESS_HEAD = 8
        const val ADDRESS_TAIL = 4
    }
}
