// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import xyz.justzappit.evm.types.Address

/** A contract this wallet's swaps may have used, and the maker of its forward swaps there. */
class SwapContract(
    val chain: AtomicSwapChainReader,
    val maker: Address,
)

/** Swap indices for both directions, skipping any a known contract shows in use, as a reset restarts the count. */
class SwapIndices(
    private val store: AtomicSwapStore,
    private val keys: AtomicSwapKeys,
    private val contracts: List<SwapContract>,
) {
    suspend fun take(): Int {
        repeat(MAX_SKIPPED) {
            val index = store.takeIndex()
            if (!usedOnChain(index)) return index
        }
        throw AtomicSwapBlockedException(AtomicSwapBlock.INDICES_IN_USE, "$MAX_SKIPPED indices in a row are in use")
    }

    // A forward swap's id binds its maker and our share; a reverse swap spends our share as its own.
    private suspend fun usedOnChain(index: Int): Boolean {
        val share = keys.userShare(index)
        val auth = keys.authAddress(index)
        return coroutineScope {
            contracts
                .flatMap { contract ->
                    listOf(
                        async { contract.chain.swap(SwapId.of(contract.maker, share)) != null },
                        async { contract.chain.makerKeyUsed(auth, share) },
                    )
                }.awaitAll()
                .any { it }
        }
    }

    private companion object {
        const val MAX_SKIPPED = 16
    }
}
