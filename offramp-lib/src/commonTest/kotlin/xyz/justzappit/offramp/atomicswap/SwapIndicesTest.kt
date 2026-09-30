// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwapIndicesTest {
    @Test
    fun indicesAKnownContractShowsInUseEitherWayAreSkipped() =
        runTest {
            val chain = Chain(MAKER, forward = setOf(0, 2), reverse = setOf(1))
            val indices = SwapIndices(Store(), Keys(), listOf(SwapContract(chain, MAKER)))

            assertEquals(3, indices.take())
            assertEquals(4, indices.take())
        }

    @Test
    fun everyKnownContractIsLookedAt() =
        runTest {
            val current = Chain(MAKER)
            val legacy = Chain(LEGACY_MAKER, forward = setOf(0))
            val indices =
                SwapIndices(Store(), Keys(), listOf(SwapContract(current, MAKER), SwapContract(legacy, LEGACY_MAKER)))

            assertEquals(1, indices.take())
        }

    @Test
    fun aLongRunOfUsedIndicesIsCutShortAndTheNextLookGoesOnFromThere() =
        runTest {
            val chain = Chain(MAKER, forward = (0 until 20).toSet())
            val indices = SwapIndices(Store(), Keys(), listOf(SwapContract(chain, MAKER)))

            val blocked = assertFailsWith<AtomicSwapBlockedException> { indices.take() }
            assertEquals(AtomicSwapBlock.INDICES_IN_USE, blocked.reason)
            assertEquals(20, indices.take())
        }

    private class Chain(
        private val maker: Address,
        forward: Set<Int> = emptySet(),
        private val reverse: Set<Int> = emptySet(),
    ) : AtomicSwapChainReader {
        private val forwardIds = forward.map { SwapId.of(maker, share(it)) }

        override suspend fun swap(id: SwapId): OnChainSwap? =
            if (id in forwardIds) {
                OnChainSwap(
                    maker,
                    0,
                    SwapStage.CLAIMED,
                    true,
                    Address.ZERO,
                    0,
                    Address.ZERO,
                    0,
                    Usdc6.ofMicros(1),
                    0,
                    SwapShare.of(ByteArray(64)),
                    SwapShare.of(ByteArray(64)),
                    ByteArray(32),
                    NoteCommitment.of(ByteArray(32)),
                )
            } else {
                null
            }

        override suspend fun now() = 0L

        override suspend fun railgunAccepts(token: Address) = true

        override suspend fun lockDuration() = 600L

        override suspend fun makerKeyUsed(
            owner: Address,
            share: SwapShare
        ) = reverse.any { owner == auth(it) && share == share(it) }

        override suspend fun payoutTx(
            id: SwapId,
            near: Long
        ): TxHash? = null
    }

    private class Keys : AtomicSwapKeys {
        override suspend fun userShare(index: Int) = share(index)

        override suspend fun authAddress(index: Int) = auth(index)

        override suspend fun payoutNote(
            index: Int,
            railgunKeys: RailgunKeySource
        ) = error("not needed")

        override suspend fun accept(
            index: Int,
            railgunKeys: RailgunKeySource,
            chainId: ChainId,
            contract: Address,
            quoteId: ByteArray,
            makerShare: SwapShare,
            makerProof: ByteArray,
        ) = error("not needed")

        override suspend fun depositAddress(
            index: Int,
            makerShare: SwapShare
        ) = error("not needed")

        override suspend fun claimSecret(index: Int) = error("not needed")

        override suspend fun signLockClaim(
            index: Int,
            chainId: ChainId,
            contract: Address,
            swapId: SwapId,
            deadline: Long,
        ) = error("not needed")

        override suspend fun signPayout(
            index: Int,
            chainId: ChainId,
            contract: Address,
            swapId: SwapId,
            relayer: Address,
            fee: Usdc6,
        ) = error("not needed")
    }

    private class Store : AtomicSwapStore {
        private var next = 0

        override suspend fun takeIndex() = next++

        override suspend fun active(): AtomicSwapRecord? = null

        override suspend fun save(record: AtomicSwapRecord) = error("not needed")
    }

    private companion object {
        val MAKER = Address.parse("0x2bac02B5032e9092493814c705F156B49E288922")
        val LEGACY_MAKER = Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5")

        fun share(index: Int) = SwapShare.of(ByteArray(64) { (index + 1).toByte() })

        fun auth(index: Int) = Address.fromBytes(ByteArray(20) { (index + 1).toByte() })
    }
}
