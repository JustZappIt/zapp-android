// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapMainnet
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import kotlinx.serialization.json.Json
import xyz.justzappit.offramp.atomicswap.RelayerTerms
import xyz.justzappit.offramp.deposits.DepositTermsRead
import xyz.justzappit.offramp.deposits.depositTerms
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PrivateUsdDepositPinsTest {
    @Test
    fun `each network's deposits pin is what its live relayer names, in the deployment's dollar`() {
        val networks =
            listOf(
                Triple(AtomicSwapMainnet.deployment, MAINNET, 1_000_000L),
                Triple(AtomicSwapTestnet.deployment, TESTNET, 2_000_000L),
            )
        for ((deployment, terms, minValue) in networks) {
            val pin = checkNotNull(deployment.usdcDeposits)
            val read = assertIs<DepositTermsRead.Pinned>(terms.depositTerms(pin), pin.toString())
            assertEquals(deployment.swap.chainId, pin.chainId)
            assertEquals(deployment.privateUsdToken.address, pin.token)
            assertEquals(pin.relayer, read.terms.relayer)
            assertEquals(BigInteger.valueOf(minValue), read.terms.minValue)
        }
        // Neither network's relayer is ever taken for the other's.
        val mainnet = checkNotNull(AtomicSwapMainnet.deployment.usdcDeposits)
        val testnet = checkNotNull(AtomicSwapTestnet.deployment.usdcDeposits)
        assertEquals(DepositTermsRead.OtherDeployment, TESTNET.depositTerms(mainnet))
        assertEquals(DepositTermsRead.OtherDeployment, MAINNET.depositTerms(testnet))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        // Each relayer's GET /v1/terms on 2026-10-23, the parts deposits read.
        val MAINNET: RelayerTerms =
            json.decodeFromString(
                """{"relayer":"0xcbcb5f57c79c88a0e4bb450289de2088958a017f","chainId":1,
                "contract":"0xeb3a00bcb6c9ff626fe57a5dbbdcf01730e7898f","fee":"500000",
                "deposits":{"contract":"0xebc7508e20bd4467491ee7e7589d5c7d4c5e8cb6",
                "relayer":"0x6ae40b34c32d3feaa0eef53d7e4724160b7a2135",
                "token":"0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48","fee":"105554","feeBps":10,
                "feeExpiresAt":1792800159,"minValue":"1000000","maxValidity":86400,"maxGasPriceWei":"20000000000"}}""",
            )
        val TESTNET: RelayerTerms =
            json.decodeFromString(
                """{"relayer":"0xd9633572041886fa7584a2e12f36c8c7f1126412","chainId":11155111,
                "contract":"0x459b0a69e0f736666b66a3ffe0fc17c11f05980d","fee":"100000",
                "deposits":{"contract":"0x58324e9a866c9690aca08169d3c67630e84c9019",
                "relayer":"0xedc4c88a3f6ae8130a2c18babbabd8100cc1a446",
                "token":"0x64c4a5cb09fe0b706d4cf24dc0a45fff275d727c","fee":"100000","feeBps":10,
                "feeExpiresAt":1792800160,"minValue":"2000000","maxValidity":86400,"maxGasPriceWei":"50000000000"}}""",
            )
    }
}
