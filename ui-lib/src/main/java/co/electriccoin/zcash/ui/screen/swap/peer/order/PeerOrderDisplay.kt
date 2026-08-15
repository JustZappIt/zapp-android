package co.electriccoin.zcash.ui.screen.swap.peer.order

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.ellipsizeMiddle
import co.electriccoin.zcash.ui.design.util.stringRes
import xyz.justzappit.evm.math.DecimalRounding
import xyz.justzappit.evm.math.decimalSetScale
import xyz.justzappit.evm.math.decimalStripTrailingZeros
import xyz.justzappit.evm.math.decimalToPlainString
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.Rate1e18

internal fun Usdc6.display(): String = toDisplayString(stripTrailingZeros = true)

internal fun usdcAmount(amount: Usdc6): StringResource =
    stringRes(R.string.p2p_transactions_row_amount_usdc, amount.display())

internal fun Rate1e18.display(currency: PeerCurrency): String {
    val scaled = decimalSetScale(decimal, currency.precision + RATE_EXTRA_DIGITS, DecimalRounding.HALF_UP)
    return decimalToPlainString(decimalStripTrailingZeros(scaled))
}

internal fun String.shortHash(): String =
    ellipsizeMiddle(prefix = HASH_ELLIPSIS_PREFIX, suffix = HASH_ELLIPSIS_SUFFIX)

private const val RATE_EXTRA_DIGITS = 2
private const val HASH_ELLIPSIS_PREFIX = 8
private const val HASH_ELLIPSIS_SUFFIX = 4
