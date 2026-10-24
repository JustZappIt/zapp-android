// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.receive

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositExample
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositLine
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositPhase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositTerms
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDeposits
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositsRepository
import co.electriccoin.zcash.ui.common.privateusd.basisPoints
import co.electriccoin.zcash.ui.common.privateusd.privateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.tokenAmount
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.about
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.RAILGUN_FEE
import xyz.justzappit.offramp.deposits.DepositRecord
import xyz.justzappit.offramp.deposits.DepositRescuable
import xyz.justzappit.offramp.peer.Bps
import xyz.justzappit.railgun.RailgunAddress
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateUsdReceiveVMTest {
    private val testnet = AtomicSwapTestnet.deployment
    private val token = testnet.privateUsdToken
    private val deposits = FakeDeposits()
    private val copyToClipboard = mockk<CopyToClipboardUseCase>(relaxed = true)
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private var wallet: RailgunAddress? = WALLET
    private lateinit var vm: PrivateUsdReceiveVM

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `without deposits only the Railgun address shows, for sends from a Railgun wallet on the network`() =
        runTest {
            start()

            val state = vm.state.value
            assertNull(state.usd)
            assertEquals(PrivateUsdReceiveTab.SHIELDED, state.tab)
            assertEquals(WALLET.value, state.shielded.address.value)
            assertNull(state.shielded.address.status)
            assertEquals(
                stringRes(R.string.private_usd_receive_shielded_only, stringRes(R.string.private_usd_network_sepolia)),
                state.shielded.notice,
            )
            assertEquals(listOf(stringRes(R.string.private_usd_receive_info_shielded)), state.info.steps)
            assertEquals(0, deposits.refreshes)
        }

    @Test
    fun `copying the Railgun address copies it whole, as nothing sensitive, and says so`() =
        runTest {
            start()

            vm.state.value.shielded.address
                .onCopy()

            verify { copyToClipboard(WALLET.value, isSensitive = false) }
            assertTrue(vm.state.value.shielded.address.isCopied)
        }

    @Test
    fun `a Railgun address that can't be derived says so`() =
        runTest {
            wallet = null
            start()

            val address = vm.state.value.shielded.address
            assertNull(address.value)
            assertEquals(stringRes(R.string.private_usd_receive_shielded_failed), address.status)
        }

    @Test
    fun `with deposits, USD shows first, with the EIP-55 address and what it takes`() =
        runTest {
            deposits.state.value = PrivateUsdDeposits(token = token, address = DEPOSIT_ADDRESS)
            start()

            val state = vm.state.value
            val usd = checkNotNull(state.usd)
            assertEquals(PrivateUsdReceiveTab.USD, state.tab)
            assertEquals("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf", usd.address.value)
            assertEquals(
                stringRes(
                    R.string.private_usd_receive_usd_only,
                    token.symbol,
                    stringRes(R.string.private_usd_network_sepolia),
                ),
                usd.notice,
            )
            assertEquals(stringRes(R.string.private_usd_receive_usd_fees_loading), usd.feesStatus)
            assertNull(usd.fees)
            assertEquals(
                stringRes(R.string.private_usd_receive_usd_available, testnet.screeningTime.about()),
                usd.notes[1],
            )
            assertEquals(
                listOf(
                    stringRes(R.string.private_usd_receive_info_usd, token.symbol),
                    stringRes(R.string.private_usd_receive_info_shielded),
                ),
                state.info.steps,
            )
        }

    @Test
    fun `the fees come from the live terms, with what a deposit brings`() =
        runTest {
            deposits.state.value =
                PrivateUsdDeposits(
                    token = token,
                    address = DEPOSIT_ADDRESS,
                    terms =
                        PrivateUsdDepositTerms(
                            minValue = units(1_000_000),
                            fee = units(113_000),
                            feeBps = Bps(10),
                            example = PrivateUsdDepositExample(sent = units(20_000_000), arrives = units(19_817_333)),
                        ),
                )
            start()

            val usd = checkNotNull(vm.state.value.usd)
            val fees = checkNotNull(usd.fees)
            assertNull(usd.feesStatus)
            assertEquals(tokenAmount(units(1_000_000), token), fees.minimum)
            assertEquals(
                stringRes(
                    R.string.private_usd_receive_usd_relayer_fee_value,
                    tokenAmount(units(113_000), token),
                    basisPoints(Bps(10)),
                ),
                fees.relayerFee,
            )
            assertEquals(
                stringRes(R.string.private_usd_receive_usd_railgun_fee_value, basisPoints(RAILGUN_FEE)),
                fees.railgunFee,
            )
            assertEquals(
                stringRes(
                    R.string.private_usd_receive_usd_example,
                    tokenAmount(units(20_000_000), token),
                    tokenAmount(units(19_817_333), token),
                ),
                fees.example,
            )
        }

    @Test
    fun `fees that couldn't be read say the money waits at the address`() =
        runTest {
            deposits.state.value = PrivateUsdDeposits(token = token, address = DEPOSIT_ADDRESS, isTermsFailed = true)
            start()

            val usd = checkNotNull(vm.state.value.usd)
            assertEquals(stringRes(R.string.private_usd_receive_usd_fees_failed), usd.feesStatus)
        }

    @Test
    fun `each address holding money gets a line, its amount hidden with the balances`() =
        runTest {
            deposits.state.value =
                PrivateUsdDeposits(
                    token = token,
                    address = DEPOSIT_ADDRESS,
                    terms = PrivateUsdDepositTerms(minValue = units(2_000_000), fee = units(100_000), feeBps = Bps(10)),
                    lines =
                        PrivateUsdDepositPhase.entries.map {
                            PrivateUsdDepositLine(DEPOSIT_ADDRESS, it, units(1_500_000))
                        },
                )
            start()

            val amount = tokenAmount(units(1_500_000), token).asPrivacySensitive()
            val minimum = tokenAmount(units(2_000_000), token)
            assertEquals(
                listOf(
                    PrivateUsdReceiveLineState(
                        stringRes(R.string.private_usd_receive_line_received, amount),
                        PrivateUsdReceiveLineTone.ACTIVE,
                    ),
                    PrivateUsdReceiveLineState(
                        stringRes(R.string.private_usd_receive_line_under_minimum, amount, minimum),
                        PrivateUsdReceiveLineTone.MUTED,
                    ),
                    PrivateUsdReceiveLineState(
                        stringRes(R.string.private_usd_receive_line_waiting, amount),
                        PrivateUsdReceiveLineTone.MUTED,
                    ),
                    PrivateUsdReceiveLineState(
                        stringRes(R.string.private_usd_receive_line_shielding, amount),
                        PrivateUsdReceiveLineTone.ACTIVE,
                    ),
                    PrivateUsdReceiveLineState(
                        stringRes(R.string.private_usd_receive_line_shielded, amount, testnet.screeningTime.about()),
                        PrivateUsdReceiveLineTone.DONE,
                    ),
                    PrivateUsdReceiveLineState(
                        stringRes(R.string.private_usd_receive_line_not_shielding, amount),
                        PrivateUsdReceiveLineTone.MUTED,
                    ),
                ),
                checkNotNull(vm.state.value.usd).lines,
            )
        }

    @Test
    fun `the USD tab looks at the deposit addresses as it opens, then on a timer, and stops when it's left`() =
        runTest {
            deposits.state.value = PrivateUsdDeposits(token = token, address = DEPOSIT_ADDRESS)
            start()
            assertEquals(1, deposits.refreshes)

            advanceTimeBy(15.seconds)
            runCurrent()
            assertEquals(2, deposits.refreshes)

            vm.state.value.onSelectTab(PrivateUsdReceiveTab.SHIELDED)
            advanceTimeBy(60.seconds)
            runCurrent()
            assertEquals(PrivateUsdReceiveTab.SHIELDED, vm.state.value.tab)
            assertEquals(2, deposits.refreshes)

            vm.state.value.onSelectTab(PrivateUsdReceiveTab.USD)
            assertEquals(3, deposits.refreshes)
        }

    @Test
    fun `when deposits go away, the Railgun address shows whatever tab was picked`() =
        runTest {
            deposits.state.value = PrivateUsdDeposits(token = token, address = DEPOSIT_ADDRESS)
            start()
            vm.state.value.onSelectTab(PrivateUsdReceiveTab.USD)

            deposits.state.value = null

            assertEquals(PrivateUsdReceiveTab.SHIELDED, vm.state.value.tab)
            assertNull(vm.state.value.usd)
            assertFalse(stringRes(R.string.private_usd_receive_info_usd, token.symbol) in vm.state.value.info.steps)
        }

    @Test
    fun `back goes back`() =
        runTest {
            start()

            vm.state.value.onBack()

            verify { navigation.back() }
        }

    private fun TestScope.start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val railgun = wallet
        vm =
            PrivateUsdReceiveVM(
                atomicSwapRepository = mockk<AtomicSwapRepository> { every { deployment } returns testnet },
                depositsRepository = deposits,
                railgunMnemonicProvider =
                    mockk<RailgunMnemonicProvider> {
                        every { walletChanges } returns flowOf(Unit)
                        if (railgun != null) {
                            coEvery { address() } returns railgun
                        } else {
                            coEvery { address() } throws IllegalStateException("no Railgun wallet here")
                        }
                    },
                copyToClipboard = copyToClipboard,
                navigationRouter = navigation,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }

    private fun units(amount: Long): BigInteger = BigInteger.valueOf(amount)

    private class FakeDeposits : PrivateUsdDepositsRepository {
        override val state = MutableStateFlow<PrivateUsdDeposits?>(null)
        override val history = flowOf(emptyList<DepositRecord>())

        var refreshes = 0

        override fun refresh() {
            refreshes++
        }

        override fun onAppUnlocked(isUnlocked: Boolean) = Unit

        override suspend fun rescuable(): List<DepositRescuable> = emptyList()

        override suspend fun rescue(index: Int) = Unit

        override suspend fun ownAddresses(): Set<Address> = emptySet()

        override suspend fun reset() = Unit
    }

    private companion object {
        val WALLET =
            RailgunAddress(
                "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                    "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
            )

        // The key whose scalar is 1, written in lowercase: the page shows it in EIP-55.
        val DEPOSIT_ADDRESS = Address.parse("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf")
    }
}
