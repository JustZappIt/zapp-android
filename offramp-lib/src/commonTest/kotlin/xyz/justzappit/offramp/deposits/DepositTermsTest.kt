// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DepositTermsTest {
    @Test
    fun `a deposit's fee is the relayer's and its rounded-up share, as zecswap-api's deposit_fee`() {
        assertEquals(units(125_000), DepositFees.fee(units(100_000), 10, units(25_000_000)))
        assertEquals(units(100_001), DepositFees.fee(units(100_000), 10, units(1)))
        assertEquals(units(10), DepositFees.fee(units(0), 10, units(9_999)))
        assertEquals(units(10), DepositFees.fee(units(0), 10, units(10_000)))
        assertEquals(units(11), DepositFees.fee(units(0), 10, units(10_001)))
        assertEquals(units(100_000), DepositFees.fee(units(100_000), 0, units(25_000_000)))
    }

    @Test
    fun `the cap is 2 USDC and half a percent, and grows with the deposit`() {
        assertEquals(units(2_000_000), DepositFees.cap(units(0)))
        assertEquals(units(2_000_001), DepositFees.cap(units(1)))
        assertEquals(units(7_000_000), DepositFees.cap(units(1_000_000_000)))
        assertEquals(units(7_000_000), DepositFees.capped(terms(fee = 2_000_000, feeBps = 50), units(1_000_000_000)))
        assertNull(DepositFees.capped(terms(fee = 2_000_001, feeBps = 50), units(1_000_000_000)))
        assertNull(DepositFees.capped(terms(fee = 0, feeBps = 51), units(1_000_000_000_000)))
        // A fee that grows with the value stays under a cap that grows too: 0.6% is over it only for a large deposit.
        assertEquals(units(1_060_000), DepositFees.capped(terms(fee = 1_000_000, feeBps = 60), units(10_000_000)))
        assertNull(DepositFees.capped(terms(fee = 1_000_000, feeBps = 60), units(1_000_000_000_000)))
    }

    @Test
    fun `a fee of the whole value or more leaves nothing to shield, and is refused`() {
        assertNull(DepositFees.capped(terms(fee = 100_000, feeBps = 0), units(100_000)))
        assertNull(DepositFees.capped(terms(fee = 100_000, feeBps = 0), units(99_999)))
        assertEquals(units(100_000), DepositFees.capped(terms(fee = 100_000, feeBps = 0), units(100_001)))
    }

    @Test
    fun `what arrives is what's shielded, less Railgun's quarter percent rounded down`() {
        // Veylor's known answer, at the hosted testnet's $0.10 and 0.1%.
        assertEquals(units(4_882_763), DepositFees.arrives(units(5_000_000), units(105_000)))
        // Today's mainnet fee: $20 brings $19.82.
        val mainnet = (relayerTerms(MAINNET_TERMS).depositTerms(MAINNET_PIN) as DepositTermsRead.Pinned).terms
        val fee = checkNotNull(DepositFees.capped(mainnet, units(20_000_000)))
        assertEquals(units(125_554), fee)
        assertEquals(units(19_824_760), DepositFees.arrives(units(20_000_000), fee))
        assertEquals(units(0), DepositFees.arrives(units(1), units(2)))
    }

    @Test
    fun `a rescue pays the terms' fee alone, at most 2 USDC`() {
        assertEquals(units(2_000_000), DepositFees.rescue(terms(fee = 2_000_000, feeBps = 50)))
        assertNull(DepositFees.rescue(terms(fee = 2_000_001, feeBps = 0)))
    }

    @Test
    fun `an authorization is valid an hour, or what the relayer takes less a minute`() {
        assertEquals(3_600, terms(maxValidity = 86_400).validity)
        assertEquals(3_600, terms(maxValidity = 3_660).validity)
        assertEquals(1_740, terms(maxValidity = 1_800).validity)
    }

    @Test
    fun `the live terms are each network's pin`() {
        assertEquals(
            DepositTermsRead.Pinned(
                DepositTerms(
                    relayer = SEPOLIA_PIN.relayer,
                    fee = units(100_000),
                    feeBps = 10,
                    feeExpiresAt = 1_792_800_160,
                    minValue = units(2_000_000),
                    maxValidity = 86_400,
                ),
            ),
            relayerTerms(SEPOLIA_TERMS).depositTerms(SEPOLIA_PIN),
        )
        assertEquals(
            DepositTermsRead.Pinned(
                DepositTerms(
                    relayer = MAINNET_PIN.relayer,
                    fee = units(105_554),
                    feeBps = 10,
                    feeExpiresAt = 1_792_800_159,
                    minValue = units(1_000_000),
                    maxValidity = 86_400,
                ),
            ),
            relayerTerms(MAINNET_TERMS).depositTerms(MAINNET_PIN),
        )
        // One network's terms are never the other's.
        assertEquals(DepositTermsRead.OtherDeployment, relayerTerms(SEPOLIA_TERMS).depositTerms(MAINNET_PIN))
    }

    @Test
    fun `terms for another deposits deployment are never followed`() {
        val other = "0x" + "77".repeat(20)
        listOf(
            "\"contract\":\"0x58324e9a866c9690aca08169d3c67630e84c9019\"" to "\"contract\":\"$other\"",
            "\"relayer\":\"0xedc4c88a3f6ae8130a2c18babbabd8100cc1a446\"" to "\"relayer\":\"$other\"",
            // The top-level relayer, which payouts are signed for, isn't the deposits account.
            "\"relayer\":\"0xedc4c88a3f6ae8130a2c18babbabd8100cc1a446\"" to
                "\"relayer\":\"0xd9633572041886fa7584a2e12f36c8c7f1126412\"",
            "\"token\":\"0x64c4a5cb09fe0b706d4cf24dc0a45fff275d727c\"" to "\"token\":\"$other\"",
            // The retired test dollar.
            "\"token\":\"0x64c4a5cb09fe0b706d4cf24dc0a45fff275d727c\"" to
                "\"token\":\"0x5764d0044bef5aa839e0ddafe2073421101b9ed8\"",
            "\"chainId\":11155111" to "\"chainId\":1",
        ).forEach { (field, edited) ->
            val terms = relayerTerms(SEPOLIA_TERMS.replace(field, edited))
            assertEquals(DepositTermsRead.OtherDeployment, terms.depositTerms(SEPOLIA_PIN), edited)
        }
    }

    @Test
    fun `terms that take no deposits, or none this build can sign under, take none and leave the rest readable`() {
        val deposits = SEPOLIA_TERMS.substringAfter("\"deposits\":").removeSuffix("}")
        listOf(
            SEPOLIA_TERMS.replace(",\n    \"deposits\":$deposits", ""),
            SEPOLIA_TERMS.replace(deposits, "null"),
            SEPOLIA_TERMS.replace(deposits, "\"soon\""),
            SEPOLIA_TERMS.replace("\"minValue\":\"2000000\"", "\"minValue\":2000000"),
            SEPOLIA_TERMS.replace("\"minValue\":\"2000000\"", "\"minValue\":\"0\""),
            SEPOLIA_TERMS.replace("\"fee\":\"100000\",\"feeBps\"", "\"fee\":\"-1\",\"feeBps\""),
            SEPOLIA_TERMS.replace("\"fee\":\"100000\",\"feeBps\"", "\"fee\":\"1.5\",\"feeBps\""),
            SEPOLIA_TERMS.replace("\"fee\":\"100000\",\"feeBps\"", "\"fee\":\"${HUGE}\",\"feeBps\""),
            SEPOLIA_TERMS.replace("\"feeBps\":10", "\"feeBps\":10001"),
            SEPOLIA_TERMS.replace("\"feeBps\":10", "\"feeBps\":-1"),
            // An authorization signed under these would run out before it's signed again.
            SEPOLIA_TERMS.replace("\"maxValidity\":86400", "\"maxValidity\":1020"),
            SEPOLIA_TERMS.replace(",\"maxValidity\":86400", ""),
        ).forEach { json ->
            val terms = relayerTerms(json)
            assertEquals(ChainId.ETHEREUM_SEPOLIA, terms.chainId)
            assertEquals(DepositTermsRead.Unavailable, terms.depositTerms(SEPOLIA_PIN), json)
        }
        val later = SEPOLIA_TERMS.replace("\"maxValidity\":86400", "\"maxValidity\":86400,\"laterField\":true")
        assertIs<DepositTermsRead.Pinned>(relayerTerms(later).depositTerms(SEPOLIA_PIN))
        val shortest = SEPOLIA_TERMS.replace("\"maxValidity\":86400", "\"maxValidity\":1021")
        assertIs<DepositTermsRead.Pinned>(relayerTerms(shortest).depositTerms(SEPOLIA_PIN))
    }

    private companion object {
        // 2^120: a Railgun note holds less.
        val HUGE: BigInteger = bigIntegerValueOf(1).shiftLeft(120)

        fun terms(
            fee: Long = 100_000,
            feeBps: Int = 10,
            maxValidity: Long = 86_400,
        ) = DepositTerms(
            relayer = Address.parse("0x" + "77".repeat(20)),
            fee = units(fee),
            feeBps = feeBps,
            feeExpiresAt = null,
            minValue = units(2_000_000),
            maxValidity = maxValidity,
        )
    }
}
