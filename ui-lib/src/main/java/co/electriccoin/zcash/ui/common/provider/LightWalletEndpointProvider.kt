package co.electriccoin.zcash.ui.common.provider

import android.app.Application
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint

// TODO [#1273]: Add ChooseServer Tests #1273
// TODO [#1273]: https://github.com/Electric-Coin-Company/zashi-android/issues/1273
class LightWalletEndpointProvider(
    private val application: Application
) {
    fun getEndpoints(): List<LightWalletEndpoint> =
        if (ZcashNetwork.fromResources(application) == ZcashNetwork.Mainnet) {
            listOf(
                LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true),
                LightWalletEndpoint(host = "na.zec.rocks", port = 443, isSecure = true),
                LightWalletEndpoint(host = "sa.zec.rocks", port = 443, isSecure = true),
                LightWalletEndpoint(host = "eu.zec.rocks", port = 443, isSecure = true),
                LightWalletEndpoint(host = "ap.zec.rocks", port = 443, isSecure = true),
                LightWalletEndpoint(host = "us.zec.stardust.rest", port = 443, isSecure = true),
                LightWalletEndpoint(host = "eu.zec.stardust.rest", port = 443, isSecure = true),
            )
        } else {
            // testnet.zec.rocks:443 is app-layer dead (TCP up, no gRPC) and produces the
            // ECONNREFUSED noise we see in logcat. Cipherscan is the working community endpoint.
            // Re-add zec.rocks here only after manually verifying gRPC HEALTH responds.
            listOf(
                LightWalletEndpoint(host = "lightwalletd.testnet.cipherscan.app", port = 443, isSecure = true),
            )
        }

    fun getDefaultEndpoint() = getEndpoints().first()

    fun getDecommissionedHosts(): Set<String> =
        setOf(
            "jp.zec.stardust.rest",
            "eu2.zec.stardust.rest",
        )
}
