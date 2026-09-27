// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.AtomicSwapConfig
import xyz.justzappit.railgun.RailgunNetwork
import kotlin.time.Duration.Companion.minutes

/**
 * The phone-testing deployment on Ethereum Sepolia (zecSwap's docs/local/android.md, section 7): a
 * maker and a relayer on the developer's machine, reached over `adb reverse tcp:8787 tcp:8787` and
 * `adb reverse tcp:8788 tcp:8788`. Redeploying changes the contract and token.
 */
object AtomicSwapTestnet {
    // 0.1 of the test token, whose relayer asks 0.02.
    private const val MAX_RELAYER_FEE = 100_000L

    // The testnet maker counts a deposit after 3 confirmations, about 4 minutes; its t0 comes 13.
    private const val MIN_SECONDS_TO_T0 = 9 * 60L
    private const val CONFIRMATIONS = 3

    // The maker's unit is one whole token, and it quotes up to 20 at a time.
    private const val UNIT_BASE_UNITS = 1_000_000L
    private const val MAX_UNITS = 20
    private const val PRESET_FIVE = 5
    private const val PRESET_TEN = 10

    val deployment =
        AtomicSwapDeployment(
            config =
                AtomicSwapConfig(
                    makerUrl = "http://127.0.0.1:8787",
                    relayerUrl = "http://127.0.0.1:8788",
                    chainId = 11_155_111,
                    contract = Address.parse("0x32CE55D00E6184c385E44e6b20b76d3a8407E809"),
                    token = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8"),
                    railgunProxy = Address.parse("0xeCFCf3b4eC647c4Ca6D49108b311b7a7C9543fea"),
                    maxRelayerFee = bigIntegerValueOf(MAX_RELAYER_FEE),
                    minSecondsToT0 = MIN_SECONDS_TO_T0,
                ),
            railgunNetwork = RailgunNetwork.SEPOLIA,
            ethereumRpcUrl = "https://ethereum-sepolia-rpc.publicnode.com",
            explorerTxUrl = "https://sepolia.etherscan.io/tx/",
            unitBaseUnits = UNIT_BASE_UNITS,
            presetUnits = listOf(1, PRESET_FIVE, PRESET_TEN, MAX_UNITS),
            maxUnits = MAX_UNITS,
            makerConfirmations = CONFIRMATIONS,
            screeningTime = 1.minutes,
        )
}
