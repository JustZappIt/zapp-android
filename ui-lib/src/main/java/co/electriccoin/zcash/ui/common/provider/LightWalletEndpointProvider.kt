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
            // The two testnet endpoints have swapped places since this list was last touched
            // (re-verified 2026-08-21). testnet.zec.rocks answers GetLightdInfo with
            // grpc-status 0 — ECC LightWalletD v0.5.3 on chain "test", behind Zebra 6.3.0.
            //
            // lightwalletd.testnet.cipherscan.app is out because its TLS front end negotiates
            // http/1.1 only and answers an ALPN "h2" offer with `no application protocol`
            // (alert 120). gRPC requires HTTP/2 over ALPN, so no client can reach it however
            // long it is left in the list — the REST API on the same IP still serving 200 is not
            // evidence to the contrary, since that is HTTP/1.1. Re-add it only when this prints
            // `ALPN protocol: h2`:
            //
            //   echo | openssl s_client -connect lightwalletd.testnet.cipherscan.app:443 -alpn h2
            listOf(
                LightWalletEndpoint(host = "testnet.zec.rocks", port = 443, isSecure = true),
            )
        }

    fun getDefaultEndpoint() = getEndpoints().first()

    /**
     * Hosts a persisted wallet must be moved off, migrated on launch by
     * `WalletRepository.migrateDecommissionedEndpointIfNeeded`.
     *
     * Changing [getEndpoints] alone is not enough: the chosen endpoint lives in
     * `PersistableWallet.endpoint`, so a wallet created earlier keeps pointing at whatever it was
     * given and never picks up a new default.
     */
    fun getDecommissionedHosts(): Set<String> =
        setOf(
            "jp.zec.stardust.rest",
            "eu2.zec.stardust.rest",
            // Serves http/1.1 only, so it can never carry gRPC. See getEndpoints().
            "lightwalletd.testnet.cipherscan.app",
        )
}
