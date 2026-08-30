// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.peer.PayeeHash
import xyz.justzappit.offramp.peer.PeerCashOutCheckpoint
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The checkpoint book is the only record of USDC that may already have been broadcast, so every
 * assertion here is about a way that record could be lost or attributed to the wrong attempt —
 * either of which costs the user the order amount.
 */
class ApplePeerStorageTest {
    @Test
    fun `attempts keep their own transaction hashes`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            book.store(checkpoint(ID_A, amountMicros = "20000000", createTx = TX_A))
            book.store(checkpoint(ID_B, amountMicros = "50000000", createTx = TX_B))

            assertEquals(TX_A, book.get(PeerCashOutId.of(ID_A))?.createDepositTxHash)
            assertEquals("20000000", book.get(PeerCashOutId.of(ID_A))?.amountMicroDecimal)
            assertEquals(TX_B, book.get(PeerCashOutId.of(ID_B))?.createDepositTxHash)
            assertEquals("50000000", book.get(PeerCashOutId.of(ID_B))?.amountMicroDecimal)
        }

    @Test
    fun `re-storing an attempt replaces only its own entry`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            book.store(checkpoint(ID_A))
            book.store(checkpoint(ID_B))
            book.store(checkpoint(ID_A, createTx = TX_A))

            assertEquals(2, book.all().size)
            assertEquals(TX_A, book.get(PeerCashOutId.of(ID_A))?.createDepositTxHash)
            assertEquals(null, book.get(PeerCashOutId.of(ID_B))?.createDepositTxHash)
        }

    @Test
    fun `clearing one settled attempt leaves the others recoverable`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            listOf(ID_A, ID_B, ID_C).forEach { book.store(checkpoint(it)) }
            book.clear(PeerCashOutId.of(ID_B))

            assertEquals(listOf(ID_A, ID_C).sorted(), book.all().map { it.id.value }.sorted())
        }

    /**
     * The read-modify-write the book serialises. Single-threaded this cannot interleave at all, so
     * the fan-out runs on a real dispatcher: without the book's lock the writes race and the losing
     * ones vanish, which on device is a lost recovery record for money already broadcast.
     */
    @Test
    fun `concurrent attempts do not overwrite each other`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            val ids = (0 until CONCURRENT_ATTEMPTS).map { it.toString(HEX).padStart(ID_A.length, '0') }

            withContext(Dispatchers.Default) {
                ids.map { async { book.store(checkpoint(it)) } }.awaitAll()
            }

            assertEquals(ids.sorted(), book.all().map { it.id.value }.sorted())
        }

    /**
     * A book this build cannot read is still the recovery authority for funds that may have settled.
     * Reading it as "no attempts" would let the next cash-out spend USDC an unresolved attempt has
     * already promised, and the next write would erase the record of it.
     */
    @Test
    fun `an undecodable book fails rather than reading as no attempts`() =
        runTest {
            val storage = FakePeerStorage()
            storage.storePeerCheckpointBookJson("{\"entries\":[{\"id\":\"nonsense\"}]}")

            assertFailsWith<IllegalStateException> { ApplePeerCheckpointBook(storage).all() }
        }

    /** A handle is PII: it must never appear in the record that describes a broadcast transaction. */
    @Test
    fun `a serialized checkpoint carries the payee hash and never the handle`() =
        runTest {
            val storage = FakePeerStorage()
            ApplePeerCheckpointBook(storage).store(checkpoint(ID_A))

            val serialized = storage.peerCheckpointBookJson().value.orEmpty()
            assertEquals(false, serialized.contains(HANDLE))
            assertEquals(true, serialized.contains(PAYEE_HASH_HEX))
        }

    /**
     * Registration is per handle, so the stored hash is only reusable while it belongs to the handle
     * it was registered for. Carrying it across an edit funds a deposit that pays the previous payee.
     */
    @Test
    fun `the payee book pairs a hash with the handle it was registered for`() =
        runTest {
            val book = ApplePeerPayeeBook(FakePeerStorage())
            val first = PeerPlatform.REVOLUT.normalizeHandle(HANDLE)
            book.store(PeerPlatform.REVOLUT, first, PayeeHash.parse(PAYEE_HASH_HEX))
            book.store(PeerPlatform.ZELLE, PeerPlatform.ZELLE.normalizeHandle("someone@example.com"), null)

            assertEquals(first, book.get(PeerPlatform.REVOLUT)?.handle)
            assertEquals(PAYEE_HASH_HEX, book.get(PeerPlatform.REVOLUT)?.hash?.hex)
            assertEquals(null, book.get(PeerPlatform.ZELLE)?.hash)
            assertEquals(null, book.get(PeerPlatform.MONZO))

            book.store(PeerPlatform.REVOLUT, PeerPlatform.REVOLUT.normalizeHandle("someoneelse"), null)
            assertEquals(null, book.get(PeerPlatform.REVOLUT)?.hash)
        }

    /** Losing a payee record only costs a re-registration, so it must not take a screen down. */
    @Test
    fun `an unreadable payee book reads as no record`() =
        runTest {
            val storage = FakePeerStorage()
            storage.storePeerPayeeBookJson("not json")

            assertEquals(null, ApplePeerPayeeBook(storage).get(PeerPlatform.REVOLUT))
        }

    private fun checkpoint(
        id: String,
        amountMicros: String = "20000000",
        createTx: TxHash? = null,
    ): PeerCashOutCheckpoint =
        PeerCashOutCheckpoint(
            id = PeerCashOutId.of(id),
            platform = PeerPlatform.REVOLUT,
            currencies = listOf(PeerCurrency.EUR),
            payeeHashHex = PAYEE_HASH_HEX,
            amountMicroDecimal = amountMicros,
            createDepositTxHash = createTx,
            blockBeforeCreateDeposit = createTx?.let { BLOCK },
            createdAtMillis = 1_700_000_000_000L,
        )

    private companion object {
        const val ID_A = "0123456789abcdef0123456789abcdef"
        const val ID_B = "fedcba9876543210fedcba9876543210"
        const val ID_C = "00000000000000000000000000000001"
        val PAYEE_HASH_HEX = "0x" + "11".repeat(HASH_BYTES)
        const val HASH_BYTES = 32
        const val HANDLE = "somerevtag"
        const val BLOCK = "33000000"
        const val CONCURRENT_ATTEMPTS = 32
        const val HEX = 16
        val TX_A: TxHash = TxHash.fromHex("0x" + "aa".repeat(TxHash.LEN))
        val TX_B: TxHash = TxHash.fromHex("0x" + "bb".repeat(TxHash.LEN))
    }
}
