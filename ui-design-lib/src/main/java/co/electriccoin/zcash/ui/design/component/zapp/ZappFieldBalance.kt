package co.electriccoin.zcash.ui.design.component.zapp

data class ZappFieldBalance(
    val label: String,
    val amount: String,
    /** Fills the field with the whole balance. */
    val onClick: (() -> Unit)? = null,
)
