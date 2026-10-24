// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.deposits

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.justzappit.evm.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The deposit addresses ethers derives from the same seeds and paths, so other wallets find them: zecSwap's
 * `crates/zecswap-core/tests/deposit-vectors.json` (from `crates/zecswap-railgun/engine/deposit-vectors.cjs`), copied
 * unchanged from zecSwap `88f8dfe`. Its Rust test, `deposit_addresses_are_the_ones_ethers_derives`, replays the same.
 */
class DepositVectorsTest {
    @Test
    fun `deposit addresses are the ones ethers derives`() {
        val vectors = vectors()
        assertEquals(30, vectors.size)
        assertEquals(setOf(32, 64), vectors.map { it.seed.hexToBytes().size }.toSet())
        vectors.forEach { vector ->
            val mainnet = vector.mainnet()
            val seed = vector.seed.hexToBytes()
            assertEquals(vector.path, DepositKeys.path(mainnet, vector.account, vector.index))
            // EIP-55 too: the checksummed address, exactly as ethers writes it.
            assertEquals(vector.address, DepositKeys.address(seed, mainnet, vector.account, vector.index).checksumHex)
            DepositKeys.withKey(seed, mainnet, vector.account, vector.index) { key ->
                assertEquals(vector.address, key.address.checksumHex, vector.path)
                val digest = repeated(vector.index)
                assertEquals(key.address, signer(digest, key.sign(digest)), vector.path)
            }
        }
    }

    @Test
    fun `a window of addresses is the vectors' addresses`() {
        vectors()
            .groupBy { Triple(it.seed, it.mainnet(), it.account) }
            .forEach { (wallet, vectors) ->
                val (seed, mainnet, account) = wallet
                val window = DepositKeys.addresses(seed.hexToBytes(), mainnet, account, 0..vectors.maxOf { it.index })
                vectors.forEach { assertEquals(it.address, window[it.index].checksumHex, it.path) }
            }
    }

    private fun vectors(): List<Vector> =
        Json.decodeFromString(checkNotNull(javaClass.getResource("/deposit-vectors.json")).readText())

    @Serializable
    private data class Vector(
        val seed: String,
        val network: String,
        val account: Int,
        val index: Int,
        val path: String,
        val address: String,
    ) {
        fun mainnet(): Boolean =
            when (network) {
                "mainnet" -> true
                "testnet" -> false
                else -> error("unknown network $network")
            }
    }
}
