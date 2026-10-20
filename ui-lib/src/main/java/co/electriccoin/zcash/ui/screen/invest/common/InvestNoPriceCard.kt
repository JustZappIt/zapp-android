package co.electriccoin.zcash.ui.screen.invest.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.TradingSchedule
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappNotice
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import java.time.Instant

@Composable
internal fun InvestNoPriceCard(state: InvestNoPriceState) {
    ZappNotice(
        title = stringResource(R.string.invest_no_price_title),
        body = listOfNotNull(state.body.getValue(), state.reopen?.getValue()).joinToString("\n\n"),
    ) {
        ZappButton(
            text = stringResource(R.string.invest_try_again),
            variant = ZappButtonVariant.Ghost,
            loading = state.isRetrying,
            enabled = !state.isRetrying,
            modifier = Modifier.weight(1f),
            onClick = state.onTryAgain,
        )
    }
}

/** 1Click had no liquidity; the amount is kept and nothing is sent. */
internal data class InvestNoPriceState(
    val body: StringResource,
    /** When a weekday stock's trading reopens, outside its window. */
    val reopen: StringResource?,
    val isRetrying: Boolean,
    val onTryAgain: () -> Unit,
)

/** For a weekday stock outside its trading window, when it reopens in the phone's time. */
internal fun InvestAsset.reopenText(now: Instant): StringResource? {
    if (schedule != TradingSchedule.WEEKDAYS || UsMarketHours.isWeekdayWindowOpen(now)) return null
    return stringRes(
        R.string.invest_no_price_reopen,
        InvestFormat.localDayTime(UsMarketHours.nextWeekdayWindowOpen(now)),
    )
}
