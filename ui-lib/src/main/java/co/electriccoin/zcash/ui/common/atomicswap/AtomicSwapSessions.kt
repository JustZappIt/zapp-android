// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import io.ktor.client.HttpClient
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.AtomicSwapChain
import xyz.justzappit.offramp.atomicswap.AtomicSwapDriver
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.MakerClient
import xyz.justzappit.offramp.atomicswap.RelayerClient

class AtomicSwapSessions(
    private val http: HttpClient,
    private val keys: AtomicSwapKeysImpl,
    private val zcash: AtomicSwapZcashImpl,
    private val store: AtomicSwapStoreImpl,
) {
    private val sessions = mutableMapOf<AtomicSwapDeployment, Session>()
    val current: AtomicSwapDriver get() = session(AtomicSwapTestnet.deployment).driver

    fun forRecord(record: AtomicSwapRecord): Session = session(deploymentFor(record))

    private fun session(deployment: AtomicSwapDeployment): Session =
        synchronized(sessions) {
            sessions.getOrPut(deployment) {
                val config = deployment.config
                val chain =
                    AtomicSwapChain(
                        BaseRpcClient(RpcHttpClient.create(), deployment.ethereumRpcUrl),
                        config.contract,
                        config.railgunProxy
                    )
                Session(
                    AtomicSwapDriver(
                        config,
                        MakerClient(http, config.makerUrl),
                        RelayerClient(http, config.relayerUrl),
                        chain,
                        keys,
                        zcash,
                        store
                    ),
                    chain
                )
            }
        }

    data class Session(
        val driver: AtomicSwapDriver,
        val chain: AtomicSwapChain
    )

    companion object {
        fun deploymentFor(record: AtomicSwapRecord): AtomicSwapDeployment =
            checkNotNull(
                listOf(AtomicSwapTestnet.deployment, AtomicSwapTestnet.legacy).firstOrNull {
                    it.config.chainId == record.quote.chainId &&
                        it.config.contract == Address.parse(record.quote.contract) &&
                        it.config.token == Address.parse(record.quote.token)
                },
            ) { "unknown original swap deployment" }
    }
}
