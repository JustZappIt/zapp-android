package co.electriccoin.zcash.ui.screen.invest.home

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.invest.common.InvestTradeInProgressState
import co.electriccoin.zcash.ui.screen.invest.section.InvestHoldingRowState

internal data class InvestHomeState(
    val summary: InvestHomeSummary?,
    val torBanner: InvestTorBannerState?,
    /** Outside US regular weekday hours only. */
    val marketBanner: StringResource?,
    val sellOnly: InvestTradeInProgressState? = null,
    val pendingTrades: List<InvestPendingTradeRow>,
    val recordsUnreadable: InvestTradeInProgressState?,
    val groups: List<InvestStockGroupState>,
    val marketError: StringResource?,
    val onRetryMarket: () -> Unit,
    val onBack: () -> Unit,
)

internal data class InvestHomeSummary(
    /** Null when no holding is priced. */
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

internal data class InvestPendingTradeRow(
    val depositAddress: String,
    val title: StringResource,
    val onClick: () -> Unit,
)

internal data class InvestStockGroupState(
    val title: StringResource,
    val status: StringResource?,
    val rows: List<InvestStockRowState>,
)

internal data class InvestStockRowState(
    val key: String,
    val monogram: String,
    val name: String,
    val ticker: String,
    /** The price, or the last one seen while there is none. */
    val price: StringResource?,
    val caption: StringResource?,
    val isPriced: Boolean,
    /** Null in sell-only mode. */
    val onClick: (() -> Unit)?,
)
