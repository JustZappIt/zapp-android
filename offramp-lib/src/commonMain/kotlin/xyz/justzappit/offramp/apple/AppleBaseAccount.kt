// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import io.ktor.client.HttpClient
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.BundlerClient
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.offramp.account.Erc4337SubmitterProvider
import xyz.justzappit.offramp.account.OfframpAccountProvider
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pConfigProvider
import xyz.justzappit.offramp.config.P2pNetworkConfig

/**
 * The Base account an Apple wallet session spends from, and everything scoped to it: the owner key,
 * the smart account, the bundler, and the one [Erc4337SubmitterProvider] all of it goes through.
 *
 * Cash-out, buy, refund and top-up move USDC from the same smart account, so they must share one
 * submitter — a second one keeps its own nonce cursor, and two operations signed against the same
 * EntryPoint nonce leave one caller waiting on a receipt that never appears.
 */
class AppleBaseAccount private constructor(
    internal val httpClient: HttpClient,
    internal val network: P2pNetworkConfig,
    internal val rpc: BaseRpcClient,
    internal val smartAccounts: SmartOfframpAccountProvider,
    internal val submitters: Erc4337SubmitterProvider,
    internal val owner: EvmKey,
) {
    val networkName: String get() = network.name

    fun close() {
        owner.zeroize()
        httpClient.close()
    }

    companion object {
        @Throws(Exception::class)
        suspend fun create(
            networkName: String,
            seedPhrase: String,
            pimlicoApiKey: String,
            rpcUrl: String? = null,
            subgraphUrl: String? = null,
            sponsorshipPolicyId: String? = null,
        ): AppleBaseAccount {
            require(seedPhrase.isNotBlank()) { "seedPhrase must not be blank" }
            // Use the same production client defaults as Android. A bare native Ktor client has no
            // ContentNegotiation plugin, so JsonObject RPC/subgraph request bodies fail before
            // reaching the network.
            val http = RpcHttpClient.create()
            var owner: EvmKey? = null
            try {
                val network = P2pConfigProvider(networkName, rpcUrl, subgraphUrl).current()
                val rpc = BaseRpcClient(http, network.rpcUrl)
                val mnemonic = seedPhrase.toCharArray()
                val key =
                    try {
                        // Keep this explicit and symmetric with Android's
                        // StaticOfframpAccountProvider fixedAccountIndex default. The same Zcash
                        // wallet mnemonic must always recover the same Base owner at
                        // m/44'/60'/0'/0/0 on both platforms.
                        EvmKeyDerivation.derive(mnemonic, accountIndex = 0)
                    } finally {
                        mnemonic.fill('\u0000')
                    }
                owner = key
                val accountProvider =
                    object : OfframpAccountProvider {
                        override suspend fun nextOfframpAccount() = key
                    }
                val smartAccounts = SmartOfframpAccountProvider(accountProvider, rpc, network.accountFactoryAddress)
                val bundler =
                    BundlerClient(
                        httpClient = http,
                        bundlerUrl = BundlerClient.urlFor(network.chainId, pimlicoApiKey),
                        entryPoint = network.entryPointAddress,
                        chainId = network.chainId,
                        sponsorshipPolicyId = sponsorshipPolicyId?.takeIf { it.isNotBlank() },
                    )
                return AppleBaseAccount(
                    httpClient = http,
                    network = network,
                    rpc = rpc,
                    smartAccounts = smartAccounts,
                    submitters = Erc4337SubmitterProvider(rpc, bundler, network, smartAccounts),
                    owner = key,
                )
            } catch (
                // Every construction failure must zero the owner key and close the HTTP client.
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                owner?.zeroize()
                http.close()
                throw error
            }
        }
    }
}
