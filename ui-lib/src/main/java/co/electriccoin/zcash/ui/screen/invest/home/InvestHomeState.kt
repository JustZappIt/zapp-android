package co.electriccoin.zcash.ui.screen.invest.home

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.invest.section.InvestHoldingRowState

internal data class InvestHomeState(
    /** Null until the holdings have loaded once. */
    val summary: InvestHomeSummary?,
    /** Shown each time Invest home opens while Tor is off, until dismissed; it never blocks anything. */
    val torBanner: InvestTorBannerState?,
    /** "Prices can be patchy": outside US regular weekday hours only. */
    val marketBanner: StringResource?,
    /** Buys that were sent but haven't finished; each opens its progress screen. */
    val pendingBuys: List<InvestPendingBuyRow>,
    val groups: List<InvestStockGroupState>,
    /** Set when prices couldn't be loaded and there are none to show. */
    val marketError: StringResource?,
    val onRetryMarket: () -> Unit,
    val onBack: () -> Unit,
)

internal data class InvestHomeSummary(
    /** Null when none of the holdings has a price right now. */
    val total: StringResource?,
    val rows: List<InvestHoldingRowState>,
    val updatedAtEpochMillis: Long,
    val isStale: Boolean,
    val onRetry: () -> Unit,
)

internal data class InvestTorBannerState(
    val onTurnOn: () -> Unit,
    val onDismiss: () -> Unit,
)

internal data class InvestPendingBuyRow(
    val depositAddress: String,
    val onClick: () -> Unit,
)

internal data class InvestStockGroupState(
    val title: StringResource,
    /** "Closed" on the Weekdays group outside Ondo's 24/5 window. */
    val status: StringResource?,
    val rows: List<InvestStockRowState>,
)

internal data class InvestStockRowState(
    val key: String,
    val monogram: String,
    val name: String,
    val ticker: String,
    /** The price, or the last one seen while there is none; null when neither is known. */
    val price: StringResource?,
    /** "per share", "last price" or "No price right now". */
    val caption: StringResource?,
    val isPriced: Boolean,
    val onClick: () -> Unit,
)
