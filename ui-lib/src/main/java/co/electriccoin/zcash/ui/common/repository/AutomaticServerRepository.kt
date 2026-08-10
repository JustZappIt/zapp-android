package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider

/**
 * Whether the wallet points at one of the bundled lightwalletd endpoints rather than a custom one.
 * The fork has no automatic server-selection preference (upstream MOB-1144 is not ported), so this
 * resolves the same way upstream's `resolveIsServerSelectionAutomatic` does for a null preference:
 * bundled or unset endpoint counts as automatic, anything else as a custom server.
 *
 * Migration reads it to decide whether the transfer can be broadcast over Tor at all.
 */
interface AutomaticServerRepository {
    suspend fun isServerAutomatic(): Boolean
}

class AutomaticServerRepositoryImpl(
    private val persistableWalletProvider: PersistableWalletProvider,
    private val lightWalletEndpointProvider: LightWalletEndpointProvider,
) : AutomaticServerRepository {
    override suspend fun isServerAutomatic(): Boolean {
        val currentEndpoint = persistableWalletProvider.getPersistableWallet()?.endpoint
        return currentEndpoint == null || currentEndpoint in lightWalletEndpointProvider.getEndpoints()
    }
}
