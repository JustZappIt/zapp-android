package co.electriccoin.zcash.ui.design.component.zapp

import androidx.annotation.DrawableRes

/** One option of a [ZappSegmentedSelector]: a label, optionally preceded by an asset or chain icon. */
data class ZappSegment(
    val label: String,
    @param:DrawableRes val icon: Int? = null,
)
