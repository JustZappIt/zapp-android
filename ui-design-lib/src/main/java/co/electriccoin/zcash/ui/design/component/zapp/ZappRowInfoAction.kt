package co.electriccoin.zcash.ui.design.component.zapp

/**
 * A tappable explanation for a row's number. One type rather than two optional parameters: a
 * handler without a description ships an unlabelled button, and the pair is only ever correct
 * together.
 */
data class ZappRowInfoAction(
    val onClick: () -> Unit,
    val contentDescription: String,
)
