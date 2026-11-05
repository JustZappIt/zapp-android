// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.atomicswap.DepositShieldNote
import xyz.justzappit.atomicswap.RailgunSeed
import xyz.justzappit.evm.deposits.DepositNote
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A deposit shields to a note kept by its words alone, so those words must commit to what libzecswap says. */
class PrivateUsdDepositNotesTest {
    private val seed = ByteArray(SEED_BYTES) { 7 }
    private var railgunSeed = ByteArray(0)
    private val notes =
        PrivateUsdDepositNotes(
            getWalletSeedBytes = mockk { coEvery { this@mockk() } returns seed },
            deployments = mockk { every { current } returns AtomicSwapTestnet.deployment },
        )

    @BeforeTest
    fun setUp() = mockkObject(AtomicSwap)

    @AfterTest
    fun tearDown() = unmockkObject(AtomicSwap)

    @Test
    fun `a note that commits to what libzecswap says comes back, with both seeds wiped`() =
        runTest {
            val words = DepositNote(word(1), List(BUNDLE_WORDS) { word(2) }, word(3))
            libzecswapAnswers(DepositShieldNote(words.npk, words.encryptedBundle, words.shieldKey, words.commitment))

            val note = notes.shield(index = 1, validBefore = 1_000)

            assertContentEquals(words.commitment, note.commitment)
            assertWiped()
        }

    @Test
    fun `a note whose words commit to something else is refused`() =
        runTest {
            val words = DepositNote(word(1), List(BUNDLE_WORDS) { word(2) }, word(3))
            libzecswapAnswers(DepositShieldNote(words.npk, words.encryptedBundle, words.shieldKey, word(9)))

            assertFailsWith<IllegalStateException> { notes.rescue(index = 1, nonce = 5) }
            assertWiped()
        }

    private fun libzecswapAnswers(note: DepositShieldNote) {
        every { AtomicSwap.shieldNote(any(), any(), any(), any(), any(), any()) } answers {
            railgunSeed = arg<RailgunSeed>(RAILGUN_ARG).bytes
            note
        }
        every { AtomicSwap.rescueNote(any(), any(), any(), any(), any(), any()) } answers {
            railgunSeed = arg<RailgunSeed>(RAILGUN_ARG).bytes
            note
        }
    }

    private fun assertWiped() {
        assertTrue(railgunSeed.isNotEmpty())
        assertTrue(seed.all { it == 0.toByte() }, "the Zcash seed stayed in memory")
        assertTrue(railgunSeed.all { it == 0.toByte() }, "the Railgun seed stayed in memory")
    }

    private fun word(value: Byte) = ByteArray(WORD_BYTES) { value }

    private companion object {
        const val SEED_BYTES = 64
        const val WORD_BYTES = 32
        const val BUNDLE_WORDS = 3
        const val RAILGUN_ARG = 5
    }
}
