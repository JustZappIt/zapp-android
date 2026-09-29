// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapTestnet
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import org.junit.Test
import xyz.justzappit.railgun.RailgunNetwork
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivateUsdReverseBalanceTest {
    private val token = checkNotNull(PrivateUsdTokens.find(RailgunNetwork.SEPOLIA, ReverseSwapTestnet.deployment.token))

    @Test
    fun unloadedBalanceIsUnknownAndSyncedEmptyBalanceIsZero() {
        assertNull(PrivateUsdBalanceState().reverseAvailable())
        assertNull(PrivateUsdBalanceState(refreshFailed = true).reverseAvailable())
        assertEquals(BigInteger.ZERO, state(emptyList()).reverseAvailable())
    }

    @Test
    fun onlyPinnedTokenSpendableFundsAreAvailable() {
        val held =
            PrivateUsdAsset(token, available = units(1234567), arriving = units(9000000), blocked = units(5000000))
        val other = PrivateUsdAsset(PrivateUsdTokens.of(RailgunNetwork.SEPOLIA).last(), available = units(99999999))
        assertEquals(units(1234567), state(listOf(held, other)).reverseAvailable())
        assertEquals(BigInteger.ZERO, state(listOf(other)).reverseAvailable())
        assertEquals(BigInteger.ZERO, state(listOf(held.copy(available = BigInteger.ZERO))).reverseAvailable())
    }

    private fun state(assets: List<PrivateUsdAsset>) = PrivateUsdBalanceState(balances = PrivateUsdBalances(assets))

    private fun units(value: Long) = BigInteger.valueOf(value)
}
