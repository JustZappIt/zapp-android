// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.common.privateusd.Sepolia
import io.ktor.http.Url
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.SwapDeployment
import xyz.justzappit.offramp.atomicswap.SwapZcashNetwork
import xyz.justzappit.offramp.atomicswap.ZcashDepositTerms
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.railgun.RailgunNetwork
import kotlin.time.Duration.Companion.minutes

object AtomicSwapTestnet {
    // 0.1 of the test token, whose relayer asks 0.02.
    private const val MAX_RELAYER_FEE = 100_000L

    // The testnet maker counts a deposit after 3 confirmations, about 4 minutes; its t0 comes 13.
    private const val CONFIRMATIONS = 3
    private const val MIN_SECONDS_TO_T0 = 9 * 60L

    // In the token's base units; the hosted services start above the most a relayer may keep.
    private const val MIN_AMOUNT = 110_000L
    private const val LEGACY_MIN_AMOUNT = 30_000L
    private const val MAX_AMOUNT = 20_000_000L

    /** The hosted testnet, which new conversions either way go to. */
    val deployment =
        AtomicSwapDeployment(
            swap =
                SwapDeployment(
                    makerUrl = Url("https://zecswap-testnet.pepeman931.workers.dev/maker"),
                    relayerUrl = Url("https://zecswap-testnet.pepeman931.workers.dev/relayer"),
                    rpcUrl = Sepolia.RPC_URL,
                    chainId = Sepolia.CHAIN_ID,
                    contract = Address.parse("0xbd9a37f47a988aefc4d80395727f41feb698e225"),
                    token = Sepolia.TEST_USD,
                    railgunProxy = Sepolia.RAILGUN_PROXY,
                    maker = Address.parse("0x2bac02b5032e9092493814c705f156b49e288922"),
                    relayer = Address.parse("0xd9633572041886fa7584a2e12f36c8c7f1126412"),
                    maxRelayerFee = Usdc6.ofMicros(MAX_RELAYER_FEE),
                    zcashNetwork = SwapZcashNetwork.TESTNET,
                    zcashConfirmations = CONFIRMATIONS,
                ),
            deposits = ZcashDepositTerms(MIN_SECONDS_TO_T0),
            railgunNetwork = RailgunNetwork.SEPOLIA,
            explorerTxUrl = Sepolia.EXPLORER_TX_URL,
            minAmount = Usdc6.ofMicros(MIN_AMOUNT),
            maxAmount = Usdc6.ofMicros(MAX_AMOUNT),
            screeningTime = 1.minutes,
        )

    /** The first, local deployment: forward swaps accepted on it keep it. */
    val legacy =
        deployment.copy(
            swap =
                deployment.swap.copy(
                    makerUrl = Url("http://127.0.0.1:8787"),
                    relayerUrl = Url("http://127.0.0.1:8788"),
                    contract = Address.parse("0x32CE55D00E6184c385E44e6b20b76d3a8407E809"),
                    maker = Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5"),
                    relayer = Address.parse("0x507d1d152025e9F6DA7Bc03B358acc247f07b4eB"),
                ),
            minAmount = Usdc6.ofMicros(LEGACY_MIN_AMOUNT),
        )
}
