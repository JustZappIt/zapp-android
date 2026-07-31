package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.p2p.P2pOrderHistoryItem
import xyz.justzappit.offramp.p2p.P2pOrderHistorySource

/**
 * Resolves the smart account, then asks [P2pOrderHistorySource] for every order placed by it.
 * Returns null on failure so the screen can render an error rather than crash.
 */
internal class GetP2pOrderHistoryUseCase(
    private val accountProvider: SmartOfframpAccountProvider,
    private val source: P2pOrderHistorySource,
) {
    suspend operator fun invoke(): List<P2pOrderHistoryItem>? =
        runCatching {
            val address = accountProvider.resolve().address
            source.fetchAll(userAddress = address)
        }.onFailure {
            Twig.warn(it) { "GetP2pOrderHistoryUseCase failed" }
        }.getOrNull()
}
