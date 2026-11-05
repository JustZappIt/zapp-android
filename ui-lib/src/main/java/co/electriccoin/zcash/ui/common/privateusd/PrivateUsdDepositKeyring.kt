// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployments
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.fingerprint
import co.electriccoin.zcash.ui.common.usecase.GetWalletSeedBytesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.justzappit.evm.deposits.DepositKey
import xyz.justzappit.evm.deposits.DepositKeys
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.SwapZcashNetwork
import xyz.justzappit.offramp.deposits.DepositKeyring
import java.util.concurrent.ConcurrentHashMap

/**
 * The wallet's deposit keys, on BIP-44's `m/44'/60'/1000'/0/index` on mainnet and `m/44'/1'/1000'/0/index` on testnet:
 * Zapp's ZIP 32 account is 0. The seed is the BIP-39 seed the swaps use, read for each use and wiped after, as
 * [co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapKeysImpl] does. Only the addresses, which are there to be
 * shared, are kept, per wallet: a scan reads many.
 */
class PrivateUsdDepositKeyring(
    private val getWalletSeedBytes: GetWalletSeedBytesUseCase,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val deployments: AtomicSwapDeployments,
) : DepositKeyring {
    @Volatile
    private var shown: ShownAddresses? = null

    override suspend fun addresses(indices: IntRange): List<Address> {
        val shown = shown()
        val missing = indices.filterNot { shown.addresses.containsKey(it) }
        if (missing.isNotEmpty()) {
            val derived = missing.min()..missing.max()
            withSeed { seed ->
                DepositKeys.addresses(seed, deployments.depositsOnMainnet(), DEPOSIT_ACCOUNT, derived)
            }.forEachIndexed { offset, address -> shown.addresses.putIfAbsent(derived.first + offset, address) }
        }
        return indices.map { shown.addresses.getValue(it) }
    }

    override suspend fun <T> withKey(
        index: Int,
        block: (DepositKey) -> T,
    ): T =
        withSeed { seed ->
            DepositKeys.withKey(seed, deployments.depositsOnMainnet(), DEPOSIT_ACCOUNT, index, block)
        }

    private suspend fun <T> withSeed(block: (ByteArray) -> T): T =
        withContext(Dispatchers.Default) {
            val seed = getWalletSeedBytes()
            try {
                block(seed)
            } finally {
                seed.fill(0)
            }
        }

    // Kept for one wallet at a time: another wallet's indices are other addresses.
    private suspend fun shown(): ShownAddresses {
        val wallet = persistableWalletProvider.requirePersistableWallet().fingerprint()
        return shown?.takeIf { it.wallet == wallet } ?: ShownAddresses(wallet).also { shown = it }
    }

    private class ShownAddresses(
        val wallet: String,
    ) {
        val addresses = ConcurrentHashMap<Int, Address>()
    }
}

/** Zapp's ZIP 32 account, whose deposit addresses take BIP-44 account 1000. */
internal const val DEPOSIT_ACCOUNT = 0

/** Whether deposit keys derive on mainnet's coin type: testnet's keeps testnet and mainnet addresses unlinked. */
internal fun AtomicSwapDeployments.depositsOnMainnet(): Boolean =
    checkNotNull(current) { "no deposits on this network" }.swap.zcashNetwork == SwapZcashNetwork.MAINNET
