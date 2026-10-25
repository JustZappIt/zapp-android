// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.deposits.DepositLanding
import xyz.justzappit.offramp.deposits.DepositPass
import xyz.justzappit.offramp.deposits.DepositRecord
import xyz.justzappit.offramp.deposits.DepositScan
import xyz.justzappit.offramp.deposits.DepositState
import xyz.justzappit.offramp.deposits.DepositTerms
import xyz.justzappit.offramp.deposits.DepositTermsRead
import xyz.justzappit.offramp.deposits.ScannedAddress
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.Bps
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

class PrivateUsdDepositLinesTest {
    @Test
    fun `each deposit under way shows, newest first, then each address holding money with none`() {
        val shielding = depositRecord(index = 2, state = DepositState.SENT, value = 5_000_000)
        val waiting = depositRecord(index = 3, waiting = true, value = 3_000_000)
        val received = depositRecord(index = 4, value = 4_000_000)
        val pass =
            pass(
                listOf(shielding, waiting, received),
                held(0 to 1_500_000, 2 to 5_000_000, 3 to 3_000_000, 4 to 4_000_000, 5 to 2_500_000, 6 to 0),
            )

        assertEquals(
            listOf(
                line(2, PrivateUsdDepositPhase.SHIELDING, 5_000_000),
                line(3, PrivateUsdDepositPhase.WAITING, 3_000_000),
                line(4, PrivateUsdDepositPhase.RECEIVED, 4_000_000),
                line(0, PrivateUsdDepositPhase.UNDER_MINIMUM, 1_500_000),
                line(5, PrivateUsdDepositPhase.RECEIVED, 2_500_000),
            ),
            depositLines(pass, MIN_VALUE, NOW, 1.hours, ::depositAddress),
        )
    }

    @Test
    fun `a landed deposit shows what it brought while Railgun screens it, then leaves the list to Activity`() {
        val landing = DepositLanding(depositTx(1), Usdc6.ofMicros(4_895_000), Usdc6.ofMicros(105_000), NOW - 600)
        val landed = depositRecord(state = DepositState.LANDED, landing = landing)

        assertEquals(
            listOf(line(0, PrivateUsdDepositPhase.SHIELDED, 4_882_763)),
            depositLines(pass(listOf(landed)), MIN_VALUE, NOW, 1.hours, ::depositAddress),
        )
        assertEquals(emptyList(), depositLines(pass(listOf(landed)), MIN_VALUE, NOW + 3_000, 1.hours, ::depositAddress))
    }

    @Test
    fun `an address whose newest deposit was refused or reverted, or whose fee is over the cap, isn't shielding`() {
        val refused = depositRecord(index = 1, state = DepositState.REFUSED)
        val reverted = depositRecord(index = 2, state = DepositState.REVERTED)
        val held = held(1 to 5_000_000, 2 to 5_000_000, 3 to 5_000_000)

        assertEquals(
            listOf(
                line(1, PrivateUsdDepositPhase.NOT_SHIELDING, 5_000_000),
                line(2, PrivateUsdDepositPhase.NOT_SHIELDING, 5_000_000),
                line(3, PrivateUsdDepositPhase.RECEIVED, 5_000_000),
            ),
            depositLines(pass(listOf(refused, reverted), held), MIN_VALUE, NOW, 1.hours, ::depositAddress),
        )
        assertEquals(
            List(3) { line(it + 1, PrivateUsdDepositPhase.NOT_SHIELDING, 5_000_000) },
            depositLines(pass(emptyList(), held, isFeeOverCap = true), MIN_VALUE, NOW, 1.hours, ::depositAddress),
        )
    }

    @Test
    fun `nothing that moved nothing, refused, replaced or expired, is a line`() {
        val gone =
            listOf(DepositState.REFUSED, DepositState.REPLACED, DepositState.EXPIRED, DepositState.REVERTED)
                .mapIndexed { index, state -> depositRecord(index = index, state = state) }

        assertEquals(emptyList(), depositLines(pass(gone), MIN_VALUE, NOW, 1.hours, ::depositAddress))
    }

    @Test
    fun `the example is twenty dollars, or the minimum if more, after both fees`() {
        // Today's mainnet fee: $20 brings $19.82.
        val mainnet = terms(fee = 105_554, minValue = 1_000_000)
        assertEquals(
            PrivateUsdDepositTerms(
                minValue = BigInteger.valueOf(1_000_000),
                fee = BigInteger.valueOf(105_554),
                feeBps = Bps(10),
                example = PrivateUsdDepositExample(BigInteger.valueOf(20_000_000), BigInteger.valueOf(19_824_760)),
            ),
            depositTerms(mainnet),
        )
        assertEquals(
            BigInteger.valueOf(25_000_000),
            depositTerms(terms(fee = 100_000, minValue = 25_000_000)).example?.sent,
        )
        assertNull(depositTerms(terms(fee = 2_100_000, minValue = 1_000_000)).example)
    }

    private fun pass(
        records: List<DepositRecord>,
        used: List<ScannedAddress> = emptyList(),
        isFeeOverCap: Boolean = false,
    ) = DepositPass(
        terms = DepositTermsRead.Pinned(terms(fee = 100_000, minValue = 2_000_000)),
        scan = DepositScan(NOW, 0, used),
        isScanned = true,
        records = records,
        nextPoll = null,
        isFeeOverCap = isFeeOverCap,
    )

    private fun held(vararg balances: Pair<Int, Long>) =
        balances.map { (index, balance) -> ScannedAddress(index, Usdc6.ofMicros(balance)) }

    private fun line(
        index: Int,
        phase: PrivateUsdDepositPhase,
        amount: Long,
    ) = PrivateUsdDepositLine(depositAddress(index), phase, BigInteger.valueOf(amount))

    private companion object {
        const val NOW = 1_792_800_000L
        val MIN_VALUE: BigInteger = BigInteger.valueOf(2_000_000)

        fun terms(
            fee: Long,
            minValue: Long,
        ) = DepositTerms(
            relayer = Address.parse("0x" + "77".repeat(20)),
            fee = BigInteger.valueOf(fee),
            feeBps = 10,
            feeExpiresAt = null,
            minValue = BigInteger.valueOf(minValue),
            maxValidity = 86_400,
        )
    }
}
