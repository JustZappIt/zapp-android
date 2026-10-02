package co.electriccoin.zcash.ui.common.invest.demo

import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The demo build's ZEC balance: it starts at [START], a buy takes the ZEC it sends (plus the network fee), a refund
 * gives it back less the refund fee, and a sale adds its payout. [DemoAccountDataSource] shows it as the phone's
 * account balance everywhere, so buying and selling visibly move the wallet. Nothing is saved.
 */
class DemoWallet {
    private val _balance = MutableStateFlow(START)
    val balance: StateFlow<Zatoshi> = _balance.asStateFlow()

    fun spend(zec: BigDecimal) = _balance.update { Zatoshi((it.value - zec.toZats()).coerceAtLeast(0)) }

    fun receive(zec: BigDecimal) = _balance.update { Zatoshi(it.value + zec.toZats()) }

    fun reset() {
        _balance.value = START
    }

    private fun BigDecimal.toZats(): Long = movePointRight(ZEC_DECIMALS).setScale(0, RoundingMode.HALF_UP).toLong()

    companion object {
        val START = Zatoshi(900_000_000L)

        /** What a shielded send costs on the network, taken with each demo buy. */
        val NETWORK_FEE_ZEC = BigDecimal("0.0001")

        private const val ZEC_DECIMALS = 8
    }
}
