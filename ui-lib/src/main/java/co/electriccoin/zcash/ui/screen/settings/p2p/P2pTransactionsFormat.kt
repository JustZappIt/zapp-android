package co.electriccoin.zcash.ui.screen.settings.p2p

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.ellipsizeMiddle
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.OrderStatus
import xyz.justzappit.offramp.p2p.OrderType
import xyz.justzappit.offramp.p2p.P2pOrderHistoryItem
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.extractUpiVpa
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object P2pTransactionsFormat {
    private const val MILLIS_PER_SECOND = 1_000L
    private const val ZERO_BYTE = 0.toByte()
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val OFFICIAL_USDC_DISPLAY_SCALE = 3

    private val DATE_FORMAT: SimpleDateFormat =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }

    fun decodeCurrency(currencyHex: String): String =
        runCatching {
            currencyHex
                .hexToBytes()
                .takeWhile { it != ZERO_BYTE }
                .toByteArray()
                .decodeToString()
                .ifBlank { "INR" }
        }.getOrDefault("INR")

    fun timestamp(epochSeconds: Long): String =
        DATE_FORMAT.format(Date(epochSeconds * MILLIS_PER_SECOND))

    /** "1m 32s" / "45s" / "1h 4m" — null when either bound is missing or non-positive. */
    fun duration(fromEpochSec: Long?, toEpochSec: Long?): String? {
        if (fromEpochSec == null || toEpochSec == null || toEpochSec <= fromEpochSec) return null
        val total = toEpochSec - fromEpochSec
        val hours = total / SECONDS_PER_HOUR
        val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = total % SECONDS_PER_MINUTE
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun usdc(value: Usdc6, stripTrailingZeros: Boolean = false): String =
        value
            .whole
            .setScale(OFFICIAL_USDC_DISPLAY_SCALE, RoundingMode.HALF_UP)
            .let { if (stripTrailingZeros) it.stripTrailingZeros() else it }
            .toPlainString()
}

internal fun P2pOrderHistoryItem.toRow(network: P2pNetworkConfig): P2pTransactionRow {
    val currency = P2pTransactionsFormat.decodeCurrency(currencyHex)
    val paidByUpi = paidByForType(orderType)?.let { formatRecipient(it, currency) }
    val tone =
        when (status) {
            OrderStatus.COMPLETED -> P2pTransactionRow.StatusTone.Success
            OrderStatus.CANCELLED -> P2pTransactionRow.StatusTone.Cancelled
            OrderStatus.PLACED, OrderStatus.ACCEPTED, OrderStatus.PAID -> P2pTransactionRow.StatusTone.Pending
        }
    return P2pTransactionRow(
        orderId = orderId.toString(),
        typeLabel = stringRes(typeLabelRes(orderType)),
        statusLabel = stringRes(statusLabelRes(status)),
        statusTone = tone,
        amountUsdc =
            stringRes(
                R.string.p2p_transactions_row_amount_usdc,
                P2pTransactionsFormat.usdc(usdcAmount, stripTrailingZeros = true),
            ),
        amountFiat =
            stringRes(
                R.string.p2p_transactions_row_amount_fiat,
                fiatAmount.toDisplayString(stripTrailingZeros = true),
                currency,
            ),
        timestamp =
            (completedAtEpochSeconds ?: cancelledAtEpochSeconds ?: placedAtEpochSeconds)
                ?.let { stringRes(P2pTransactionsFormat.timestamp(it)) },
        explorerUrl = null,
        detail =
            TransactionDetail(
                fee =
                    fixedFeePaid?.let {
                        stringRes(
                            R.string.p2p_transactions_row_amount_usdc,
                            P2pTransactionsFormat.usdc(it),
                        )
                    },
                paidByUpiPlain = paidByUpi,
                paidToUpiPlain = paidToForType(orderType)?.let { formatRecipient(it, currency) },
                merchantAddressShort =
                    acceptedMerchantAddress
                        ?.checksumHex
                        ?.ellipsizeMiddle(prefix = ADDRESS_ELLIPSIS_PREFIX, suffix = ADDRESS_ELLIPSIS_SUFFIX),
                merchantExplorerUrl = acceptedMerchantAddress?.let { network.addressUrl(it.checksumHex) },
                duration =
                    P2pTransactionsFormat
                        .duration(
                            fromEpochSec = placedAtEpochSeconds,
                            toEpochSec = completedAtEpochSeconds ?: cancelledAtEpochSeconds,
                        )?.let(::stringRes),
            ),
    )
}

private const val ADDRESS_ELLIPSIS_PREFIX = 8
private const val ADDRESS_ELLIPSIS_SUFFIX = 4

/**
 * Display form of a recipient/merchant payment address: the VPA for UPI, verbatim otherwise, and
 * ellipsized like the merchant EVM address when the corridor's address is an opaque blob (VEN Pago
 * Móvil is base64, not a human-readable handle).
 */
private fun formatRecipient(plain: String, currencyCode: String): String {
    val extracted = extractUpiVpa(plain)
    return if (CurrencyCode.fromCodeOrNull(currencyCode)?.paymentAddressIsOpaque == true) {
        extracted.ellipsizeMiddle(prefix = ADDRESS_ELLIPSIS_PREFIX, suffix = ADDRESS_ELLIPSIS_SUFFIX)
    } else {
        extracted
    }
}

private fun P2pOrderHistoryItem.paidByForType(type: OrderType): String? =
    when (type) {
        OrderType.BUY -> null
        OrderType.PAY, OrderType.SELL -> merchantUpiPlain
    }

private fun P2pOrderHistoryItem.paidToForType(type: OrderType): String? =
    when (type) {
        OrderType.BUY -> recipientUpiPlain
        OrderType.PAY, OrderType.SELL -> recipientUpiPlain
    }

private fun typeLabelRes(orderType: OrderType): Int =
    when (orderType) {
        OrderType.BUY -> R.string.p2p_transactions_type_buy
        OrderType.SELL -> R.string.p2p_transactions_type_sell
        OrderType.PAY -> R.string.p2p_transactions_type_pay
    }

private fun statusLabelRes(status: OrderStatus): Int =
    when (status) {
        OrderStatus.PLACED -> R.string.p2p_transactions_status_placed
        OrderStatus.ACCEPTED -> R.string.p2p_transactions_status_accepted
        OrderStatus.PAID -> R.string.p2p_transactions_status_paid
        OrderStatus.COMPLETED -> R.string.p2p_transactions_status_completed
        OrderStatus.CANCELLED -> R.string.p2p_transactions_status_cancelled
    }
