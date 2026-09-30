package co.electriccoin.zcash.ui.design.component.zapp

data class ZappFieldBalance(
    val label: String,
    val amount: String,
    /** Makes the balance a button. */
    val action: ZappFieldBalanceAction? = null,
)

/** What tapping a [ZappFieldBalance] does, and the words a screen reader offers for it. */
data class ZappFieldBalanceAction(
    val onClickLabel: String,
    val onClick: () -> Unit,
)
