// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.screen.privateusd.toUsd
import kotlinx.serialization.json.Json
import org.junit.Test
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.SwapDeployment
import xyz.justzappit.offramp.atomicswap.SwapTokenIssuer
import xyz.justzappit.offramp.atomicswap.SwapZcashNetwork
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicSwapSessionsTest {
    @Test
    fun pendingSwapsKeepTheirContractAndServices() {
        for (deployment in listOf(AtomicSwapTestnet.deployment, AtomicSwapMainnet.deployment)) {
            assertEquals(deployment, AtomicSwapSessions.deploymentFor(record(deployment)))
        }
    }

    @Test
    fun `each deployment takes quotes from its own maker and pays through its own relayer only`() {
        val testnet = AtomicSwapTestnet.deployment.swap
        assertEquals(Address.parse("0x2bac02b5032e9092493814c705f156b49e288922"), testnet.maker)
        assertEquals(Address.parse("0xd9633572041886fa7584a2e12f36c8c7f1126412"), testnet.relayer)
        val mainnet = AtomicSwapMainnet.deployment.swap
        assertEquals(Address.parse("0x4370eeaa6becf1daef21deeb0379eb7f6caef7ef"), mainnet.maker)
        assertEquals(Address.parse("0xcbcb5f57c79c88a0e4bb450289de2088958a017f"), mainnet.relayer)
    }

    @Test
    fun `a build checks swap indices against the contracts on its own zcash network`() {
        assertEquals(listOf(AtomicSwapTestnet.deployment), AtomicSwapSessions.indexedOn(SwapZcashNetwork.TESTNET))
        assertEquals(listOf(AtomicSwapMainnet.deployment), AtomicSwapSessions.indexedOn(SwapZcashNetwork.MAINNET))
        assertEquals(emptyList(), AtomicSwapSessions.indexedOn(null))
    }

    @Test
    fun unknownDeploymentsAreNeverMappedToTheCurrentContract() {
        val old = record(AtomicSwapTestnet.deployment)
        val elsewhere = old.copy(quote = old.quote.copy(contract = Address.parse("0x" + "01".repeat(20))))
        assertFailsWith<IllegalStateException> { AtomicSwapSessions.deploymentFor(elsewhere) }
    }

    @Test
    fun `the hosted testnet is written with its confirmations and issuer, and records kept before read as they were`() {
        val current = AtomicSwapTestnet.deployment.swap
        val issuer = checkNotNull(current.tokenIssuer)
        assertEquals(current(issuer), storeJson.encodeToString(SwapDeployment.serializer(), current))
        assertEquals(
            current.copy(
                contract = Address.parse("0xbd9a37f47a988aefc4d80395727f41feb698e225"),
                escrowConfirmations = 3,
                zcashConfirmations = 3,
                tokenIssuer = null,
            ),
            storeJson.decodeFromString(SwapDeployment.serializer(), KEPT),
        )
    }

    @Test
    fun `the hosted mainnet beta is written with its network, confirmations and issuer`() {
        val mainnet = AtomicSwapMainnet.deployment.swap
        val json = mainnet(checkNotNull(mainnet.tokenIssuer))
        assertEquals(json, storeJson.encodeToString(SwapDeployment.serializer(), mainnet))
        assertEquals(mainnet, storeJson.decodeFromString(SwapDeployment.serializer(), json))
    }

    private fun record(deployment: AtomicSwapDeployment): AtomicSwapRecord {
        val swap = deployment.swap
        val record = toUsd(index = 0, at = 1, outcome = null)
        val quote = record.quote.copy(chainId = swap.chainId, contract = swap.contract, token = swap.token)
        return record.copy(quote = quote)
    }

    private companion object {
        val storeJson = Json { explicitNulls = false }
        const val KEPT =
            """{"makerUrl":"https://zecswap-testnet.pepeman931.workers.dev/maker",""" +
                """"relayerUrl":"https://zecswap-testnet.pepeman931.workers.dev/relayer",""" +
                """"rpcUrl":"https://ethereum-sepolia-rpc.publicnode.com","chainId":11155111,""" +
                """"contract":"0xbd9a37f47a988aefc4d80395727f41feb698e225",""" +
                """"token":"0x5764d0044bef5aa839e0ddafe2073421101b9ed8",""" +
                """"railgun":"0xecfcf3b4ec647c4ca6d49108b311b7a7c9543fea",""" +
                """"maker":"0x2bac02b5032e9092493814c705f156b49e288922",""" +
                """"relayer":"0xd9633572041886fa7584a2e12f36c8c7f1126412"}"""

        fun current(issuer: SwapTokenIssuer) =
            KEPT
                .replace("0xbd9a37f47a988aefc4d80395727f41feb698e225", "0xd75efc6a157cc0a95f66962da86ddf35d9f2617c")
                .removeSuffix("}") + ""","confirmations":2,"zcashConfirmations":2,""" +
                """"tokenIssuer":{"url":"https://zecswap-testnet.pepeman931.workers.dev/issuer",""" +
                """"name":"zecswap-testnet-issuer","tokenKey":"${issuer.tokenKey}",""" +
                """"returnKey":"${issuer.returnKey}"}}"""

        // Its escrows take the default 3 confirmations, so none are written.
        fun mainnet(issuer: SwapTokenIssuer) =
            """{"makerUrl":"https://zecswap-mainnet.pepeman931.workers.dev/maker",""" +
                """"relayerUrl":"https://zecswap-mainnet.pepeman931.workers.dev/relayer",""" +
                """"rpcUrl":"https://ethereum-rpc.publicnode.com","chainId":1,""" +
                """"contract":"0x4708d7d36992b7e33ad97a1ab0a31c3ac6c4fe6d",""" +
                """"token":"0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",""" +
                """"railgun":"0xfa7093cdd9ee6932b4eb2c9e1cde7ce00b1fa4b9",""" +
                """"maker":"0x4370eeaa6becf1daef21deeb0379eb7f6caef7ef",""" +
                """"relayer":"0xcbcb5f57c79c88a0e4bb450289de2088958a017f",""" +
                """"zcashNetwork":"mainnet","zcashConfirmations":2,""" +
                """"tokenIssuer":{"url":"https://zecswap-mainnet.pepeman931.workers.dev/issuer",""" +
                """"name":"zecswap-mainnet-issuer","tokenKey":"${issuer.tokenKey}",""" +
                """"returnKey":"${issuer.returnKey}"}}"""
    }
}
