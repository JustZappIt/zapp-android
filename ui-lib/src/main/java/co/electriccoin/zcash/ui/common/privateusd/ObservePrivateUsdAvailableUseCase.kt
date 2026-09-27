// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.repository.ConfigurationRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.configuration.ConfigurationEntries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Private USD shows only in builds with a swap deployment and a Railgun network, and while its flag is on. */
class ObservePrivateUsdAvailableUseCase(
    private val configurationRepository: ConfigurationRepository,
    private val atomicSwapRepository: AtomicSwapRepository,
    private val railgunWalletRepository: RailgunWalletRepository,
) {
    operator fun invoke(): Flow<Boolean> =
        if (atomicSwapRepository.deployment == null || railgunWalletRepository.state.value.network == null) {
            flowOf(false)
        } else {
            configurationRepository.configurationFlow
                .filterNotNull()
                .map { ConfigurationEntries.IS_PRIVATE_USD_AVAILABLE.getValue(it) }
                .distinctUntilChanged()
        }
}
