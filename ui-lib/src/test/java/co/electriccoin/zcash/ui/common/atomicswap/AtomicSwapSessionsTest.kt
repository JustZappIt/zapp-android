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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicSwapSessionsTest {
    @Test
    fun pendingSwapsKeepTheirContractAndServices() {
        val current = AtomicSwapTestnet.deployment
        assertEquals(current, AtomicSwapSessions.deploymentFor(record(current)))
    }

    @Test
    fun `each deployment takes quotes from its own maker and pays through its own relayer only`() {
        val current = AtomicSwapTestnet.deployment.swap
        assertEquals(Address.parse("0x2bac02b5032e9092493814c705f156b49e288922"), current.maker)
        assertEquals(Address.parse("0xd9633572041886fa7584a2e12f36c8c7f1126412"), current.relayer)
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
                """"relayer":"0xd9633572041886fa7584a2e12f36c8c7f1126412","maxRefundFee":"100000"}"""

        fun current(issuer: SwapTokenIssuer) =
            KEPT
                .replace("0xbd9a37f47a988aefc4d80395727f41feb698e225", "0xd75efc6a157cc0a95f66962da86ddf35d9f2617c")
                .removeSuffix("}") + ""","confirmations":2,"zcashConfirmations":2,""" +
                """"tokenIssuer":{"url":"https://zecswap-testnet.pepeman931.workers.dev/issuer",""" +
                """"name":"zecswap-testnet-issuer","tokenKey":"${issuer.tokenKey}",""" +
                """"returnKey":"${issuer.returnKey}"}}"""
    }
}
