// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.screen.privateusd.toUsd
import co.electriccoin.zcash.ui.screen.privateusd.toZec
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.railgun.RailgunDestination
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ObservePrivateUsdActivityUseCaseTest {
    @Test
    fun `conversions both ways and sends merge newest first`() =
        runTest {
            val paid = toUsd(index = 0, at = 100, AtomicSwapOutcome.Paid)
            val back = toZec(index = 1, ReversePhase.COMPLETE, acceptedAt = 300)
            val sent = PrivateUsdSendRecord(TX_HASH, TOKEN, amount = BigInteger.ONE, TO, sentAt = 200)
            val proving = PrivateUsdPendingSend("p", TOKEN, amount = BigInteger.ONE, TO, startedAt = 400)
            val useCase =
                ObservePrivateUsdActivityUseCase(
                    atomicSwapRepository =
                        mockk<AtomicSwapRepository> { every { history } returns flowOf(listOf(paid)) },
                    reverseSwapRepository =
                        mockk<ReverseSwapRepository> { every { history } returns flowOf(listOf(back)) },
                    sendLog =
                        mockk<PrivateUsdSendLog> {
                            every { observe } returns flowOf(PrivateUsdSendHistory(listOf(sent), listOf(proving)))
                        },
                )

            assertEquals(
                listOf(
                    PrivateUsdActivityData.Proving(proving),
                    PrivateUsdActivityData.ToZec(back),
                    PrivateUsdActivityData.Sent(sent),
                    PrivateUsdActivityData.ToUsd(paid),
                ),
                useCase().first(),
            )
        }

    private companion object {
        val TX_HASH = TxHash.fromHex("0x" + "ab".repeat(32))
        val TOKEN = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8")
        val TO = RailgunDestination.Public(Address.parse("0x1c7f9a756b08753cf8da94d394659134bb8c5539"))
    }
}
