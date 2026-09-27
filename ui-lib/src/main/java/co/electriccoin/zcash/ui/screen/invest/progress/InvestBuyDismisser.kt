package co.electriccoin.zcash.ui.screen.invest.progress

/**
 * Stops listing a buy that needs attention once the user has its support reference. It is the buy engine's
 * `InvestRepository.dismissBuy`, which this branch's copy of the contract doesn't have yet: when the two meet,
 * bind it as `InvestBuyDismisser(get<InvestRepository>()::dismissBuy)` in investViewModelModule, or call the
 * repository directly and drop this interface.
 */
fun interface InvestBuyDismisser {
    suspend fun dismiss(depositAddress: String)
}
