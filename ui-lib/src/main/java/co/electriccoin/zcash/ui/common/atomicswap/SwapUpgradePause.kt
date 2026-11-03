// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider

class SwapUpgradePauseException : IllegalStateException("conversions pause in the hour before NU7 activates")

/**
 * Turns away a conversion to private USD in the hour before NU7 activates on mainnet. A deposit
 * signed for NU6.3 that misses its block can't be mined after activation, so the conversion would
 * wait for the deposit to expire and may be called off meanwhile.
 */
class SwapUpgradePause(
    private val synchronizerProvider: SynchronizerProvider,
) {
    suspend fun requireOpen() {
        val synchronizer = synchronizerProvider.getSynchronizer()
        val tip = synchronizer.networkHeight.value?.value ?: return
        if (synchronizer.network.isMainnet() && tip + 1 in PAUSED_FROM until NU7_MAINNET_ACTIVATION) {
            throw SwapUpgradePauseException()
        }
    }

    private companion object {
        const val NU7_MAINNET_ACTIVATION = 3_543_405L
        const val PAUSED_FROM = NU7_MAINNET_ACTIVATION - 48
    }
}
