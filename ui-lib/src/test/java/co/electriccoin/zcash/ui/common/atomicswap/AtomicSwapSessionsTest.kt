// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.screen.privateusd.toUsd
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import org.junit.Test
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.SwapDeployment
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicSwapSessionsTest {
    @Test
    fun oldPendingSwapsKeepTheirOriginalContractAndServices() {
        val original = AtomicSwapTestnet.legacy
        assertEquals(original, AtomicSwapSessions.deploymentFor(record(original)))
        assertEquals(Url("http://127.0.0.1:8787"), AtomicSwapSessions.deploymentFor(record(original)).swap.makerUrl)
        val retired = AtomicSwapTestnet.retiredHosted
        assertEquals(retired, AtomicSwapSessions.deploymentFor(record(retired)))
        val current = AtomicSwapTestnet.deployment
        assertEquals(current, AtomicSwapSessions.deploymentFor(record(current)))
    }

    @Test
    fun `each deployment takes quotes from its own maker and pays through its own relayer only`() {
        val current = AtomicSwapTestnet.deployment.swap
        assertEquals(Address.parse("0x2bac02b5032e9092493814c705f156b49e288922"), current.maker)
        assertEquals(Address.parse("0xd9633572041886fa7584a2e12f36c8c7f1126412"), current.relayer)
        val legacy = AtomicSwapTestnet.legacy.swap
        assertEquals(Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5"), legacy.maker)
        assertEquals(Address.parse("0x507d1d152025e9F6DA7Bc03B358acc247f07b4eB"), legacy.relayer)
    }

    @Test
    fun unknownDeploymentsAreNeverMappedToTheCurrentContract() {
        val old = record(AtomicSwapTestnet.legacy)
        val elsewhere = old.copy(quote = old.quote.copy(contract = Address.parse("0x" + "01".repeat(20))))
        assertFailsWith<IllegalStateException> { AtomicSwapSessions.deploymentFor(elsewhere) }
    }

    @Test
    fun `the hosted testnet is written with its confirmations, and records kept before still read as they were`() {
        val current = AtomicSwapTestnet.deployment.swap
        assertEquals(CURRENT, storeJson.encodeToString(SwapDeployment.serializer(), current))
        assertEquals(
            AtomicSwapTestnet.retiredHosted.swap.copy(escrowConfirmations = 3, zcashConfirmations = 3),
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
        val CURRENT =
            KEPT
                .replace("0xbd9a37f47a988aefc4d80395727f41feb698e225", "0xa067d2e46f7cea71f4e4fc862b6444ecc1450afc")
                .removeSuffix("}") + ""","confirmations":2,"zcashConfirmations":2}"""
    }
}
