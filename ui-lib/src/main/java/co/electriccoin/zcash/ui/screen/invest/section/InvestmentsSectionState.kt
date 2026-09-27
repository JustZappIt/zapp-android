package co.electriccoin.zcash.ui.screen.invest.section

import co.electriccoin.zcash.ui.design.util.StringResource

/** What Invest adds to the PAY tab: the Investments block (or its entry card) and the speed-dial action. */
internal data class InvestPayState(
    val section: InvestmentsSectionState,
    /** Hidden when the build doesn't offer Invest, or the user's country is prohibited. */
    val isSpeedDialActionVisible: Boolean,
    val onInvestClick: () -> Unit,
) {
    companion object {
        val HIDDEN = InvestPayState(InvestmentsSectionState.Hidden, isSpeedDialActionVisible = false) {}
    }
}

internal sealed interface InvestmentsSectionState {
    /** Nothing Invest-related renders. */
    data object Hidden : InvestmentsSectionState

    /** "Invest from your shielded ZEC ›": before the gate and setup, or with nothing held yet. */
    data class Entry(
        val onClick: () -> Unit,
    ) : InvestmentsSectionState

    data class Loading(
        val onClick: () -> Unit,
    ) : InvestmentsSectionState

    /** The first load failed and there is nothing cached to show. */
    data class Error(
        val message: StringResource,
        val onRetry: () -> Unit,
    ) : InvestmentsSectionState

    data class Holdings(
        val rows: List<InvestHoldingRowState>,
        /** Null when none of the holdings has a price right now. */
        val total: StringResource?,
        val updatedAtEpochMillis: Long,
        /** The figures are the last known ones: the latest refresh failed. */
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
    /** Value first; null when there is no price right now. */
    val value: StringResource?,
    /** Shares second. */
    val units: StringResource,
    /** Invest home's Sell for this holding; null where it isn't offered (PAY, or while a sale of it runs). */
    val onSell: (() -> Unit)? = null,
    /** Set while a buy or sale of this stock runs: Sell isn't offered until it finishes. */
    val tradeInProgress: HoldingTrade? = null,
    val onClick: () -> Unit,
)

/** The pending trade that holds a holding's Sell back, and the way to it. */
internal data class HoldingTrade(
    val isSale: Boolean,
    val onOpen: () -> Unit,
)
