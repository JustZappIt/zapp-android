package co.electriccoin.zcash.ui.screen.invest.section

import co.electriccoin.zcash.ui.design.util.StringResource

internal data class InvestPayState(
    val section: InvestmentsSectionState,
    val isSpeedDialActionVisible: Boolean,
    val onInvestClick: () -> Unit,
) {
    companion object {
        val HIDDEN = InvestPayState(InvestmentsSectionState.Hidden, isSpeedDialActionVisible = false) {}
    }
}

internal sealed interface InvestmentsSectionState {
    data object Hidden : InvestmentsSectionState

    data class Entry(
        val onClick: () -> Unit,
    ) : InvestmentsSectionState

    data class Loading(
        val onClick: () -> Unit,
    ) : InvestmentsSectionState

    data class Error(
        val message: StringResource,
        val onRetry: () -> Unit,
    ) : InvestmentsSectionState

    data class Holdings(
        val rows: List<InvestHoldingRowState>,
        /** Null when no holding is priced. */
        val total: StringResource?,
        val updatedAtEpochMillis: Long,
        val isStale: Boolean,
        val onHeaderClick: () -> Unit,
        val onRetry: () -> Unit,
    ) : InvestmentsSectionState
}

internal data class InvestHoldingRowState(
    val key: String,
    val monogram: String,
    val name: String,
    val ticker: String,
    /** Null without a price. */
    val value: StringResource?,
    val units: StringResource,
    /** Null where Sell isn't offered. */
    val onSell: (() -> Unit)? = null,
    val tradeInProgress: HoldingTrade? = null,
    val onClick: () -> Unit,
)

internal data class HoldingTrade(
    val isSale: Boolean,
    val onOpen: () -> Unit,
)
