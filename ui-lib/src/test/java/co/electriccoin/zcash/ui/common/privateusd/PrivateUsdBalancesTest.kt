// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import xyz.justzappit.evm.types.Address
import xyz.justzappit.railgun.RailgunBalanceBucket
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunTokenAmount
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivateUsdBalancesTest {
    @Test
    fun `each screening status lands in its own column and spent notes are gone`() {
        val balances =
            PrivateUsdBalances.of(
                RailgunNetwork.SEPOLIA,
                mapOf(
                    RailgunBalanceBucket.SPENDABLE to listOf(amount(TEST_USD, 12_340_000)),
                    RailgunBalanceBucket.SHIELD_PENDING to listOf(amount(TEST_USD, 977_550)),
                    RailgunBalanceBucket.SHIELD_BLOCKED to listOf(amount(TEST_USD, 1_000_000)),
                    RailgunBalanceBucket.MISSING_INTERNAL_POI to listOf(amount(TEST_USD, 500_000)),
                    RailgunBalanceBucket.PROOF_SUBMITTED to listOf(amount(TEST_USD, 250_000)),
                    RailgunBalanceBucket.SPENT to listOf(amount(TEST_USD, 99_000_000)),
                ),
            )

        val asset = balances.assets.single()
        assertEquals(BigInteger.valueOf(12_340_000), asset.available)
        assertEquals(BigInteger.valueOf(977_550), asset.arriving)
        assertEquals(BigInteger.valueOf(1_000_000), asset.blocked)
        assertEquals(BigInteger.valueOf(750_000), asset.processing)
        assertEquals(BigDecimal("15.06755"), balances.total.stripTrailingZeros())
    }

    @Test
    fun `tokens the app doesn't know are left out, and only dollars count as private USD`() {
        val balances =
            PrivateUsdBalances.of(
                RailgunNetwork.SEPOLIA,
                mapOf(
                    RailgunBalanceBucket.SPENDABLE to
                        listOf(
                            amount(WETH, 1_000_000_000_000_000),
                            amount("0x0000000000000000000000000000000000000001", 5),
                            amount(TEST_USD.lowercase(), 2_000_000),
                        ),
                ),
            )

        assertEquals(listOf("tUSD", "WETH"), balances.assets.map { it.token.symbol })
        assertEquals(0, BigDecimal(2).compareTo(balances.available))
    }

    @Test
    fun `zero amounts listed by the SDK show nothing`() {
        val balances =
            PrivateUsdBalances.of(
                RailgunNetwork.SEPOLIA,
                mapOf(RailgunBalanceBucket.SHIELD_PENDING to listOf(amount(TEST_USD, 0))),
            )

        assertEquals(0, balances.arriving.signum())
    }

    @Test
    fun `a balance not loaded yet has nothing known available, and a synced one without the token has none`() {
        val token = PrivateUsdTokens.of(RailgunNetwork.SEPOLIA).first { it.isDollar }
        val synced = PrivateUsdBalanceState(balances = PrivateUsdBalances(emptyList()))

        assertNull(PrivateUsdBalanceState().available(token))
        assertNull(PrivateUsdBalanceState(refreshFailed = true).available(token))
        assertEquals(BigInteger.ZERO, synced.available(token))
    }

    @Test
    fun `only the token's spendable funds are available`() {
        val tokens = PrivateUsdTokens.of(RailgunNetwork.SEPOLIA)
        val held =
            PrivateUsdAsset(
                tokens.first(),
                available = BigInteger.valueOf(1_234_567),
                arriving = BigInteger.valueOf(9_000_000),
                blocked = BigInteger.valueOf(5_000_000),
            )
        val other = PrivateUsdAsset(tokens.last(), available = BigInteger.valueOf(99_999_999))
        val balance = PrivateUsdBalanceState(balances = PrivateUsdBalances(listOf(held, other)))

        assertEquals(BigInteger.valueOf(1_234_567), balance.available(tokens.first()))
    }

    private fun amount(
        token: String,
        value: Long
    ) = RailgunTokenAmount(Address.parse(token), BigInteger.valueOf(value))

    private companion object {
        const val TEST_USD = "0x5764D0044bef5AA839E0dDafE2073421101B9Ed8"
        const val WETH = "0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14"
    }
}
