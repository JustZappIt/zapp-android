// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardStatus
import co.electriccoin.zcash.ui.screen.gift.model.GiftCardTransitionException
import co.electriccoin.zcash.ui.screen.gift.model.StoredGiftCard
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GiftCardStorageProviderTest {
    @Test
    fun `reads back nothing before anything is written`() =
        runTest {
            assertEquals(emptyList(), storage().getAll())
        }

    @Test
    fun `persists a minted card so it survives a process restart`() =
        runTest {
            val preferences = InMemoryPreferenceProvider()
            storage(preferences).add(card())

            // A fresh instance over the same encrypted preferences is what the next launch sees.
            val reread = storage(preferences).get(ID)

            assertEquals(card(), reread)
            assertEquals(MNEMONIC, reread?.mnemonic)
        }

    @Test
    fun `observes the list as it changes`() =
        runTest {
            val storage = storage()

            assertEquals(emptyList(), storage.observe().first())
            storage.add(card())
            assertEquals(listOf(card()), storage.observe().first())
        }

    @Test
    fun `applies the transition guards`() =
        runTest {
            val storage = storage()
            storage.add(card())

            assertFailsWith<GiftCardTransitionException> { storage.markFunded(ID, "", NOW) }
            assertFailsWith<GiftCardTransitionException> { storage.markShared(ID, NOW) }
            assertFailsWith<GiftCardTransitionException> { storage.add(card()) }
            assertEquals(GiftCardStatus.DRAFT, storage.get(ID)?.status)
            assertNull(storage.get(ID)?.fundingTxid)
        }

    @Test
    fun `advances a card through funding and sharing`() =
        runTest {
            val storage = storage()
            storage.add(card())

            storage.recordFundingSubmitted(ID, TXID, NOW)
            assertEquals(GiftCardStatus.DRAFT, storage.get(ID)?.status)
            assertEquals(TXID, storage.get(ID)?.fundingTxid)

            storage.markFunded(ID, TXID, NOW)
            assertEquals(GiftCardStatus.FUNDED, storage.get(ID)?.status)

            storage.markShared(ID, NOW)
            assertEquals(GiftCardStatus.SHARED, storage.get(ID)?.status)
        }

    @Test
    fun `blocks account deletion only while unshared funds exist`() =
        runTest {
            val storage = storage()
            storage.add(card())

            assertFalse(storage.hasUnsharedFunds(ACCOUNT))
            storage.markFunded(ID, TXID, NOW)
            assertTrue(storage.hasUnsharedFunds(ACCOUNT))
            assertFalse(storage.hasUnsharedFunds("another-account"))
            storage.markShared(ID, NOW)
            assertFalse(storage.hasUnsharedFunds(ACCOUNT))
        }

    @Test
    fun `blocks the wallet wipe while any account holds unshared funds`() =
        runTest {
            val storage = storage()
            storage.add(card())

            assertFalse(storage.hasUnsharedFunds())
            storage.recordFundingSubmitted(ID, TXID, NOW)
            // Submitted is enough: the money has already left the sender's wallet.
            assertTrue(storage.hasUnsharedFunds())
            storage.markShared(ID, NOW)
            assertFalse(storage.hasUnsharedFunds())
        }

    @Test
    fun `serialises concurrent mutations instead of losing cards`() =
        runTest {
            val storage = storage()
            // Flagged as attempted, because an abandoned draft is deliberately superseded by the
            // next mint. The records this must never lose are the ones money has moved for.
            (0 until CONCURRENT).forEach { index ->
                storage.add(card(id = "card-$index"))
                storage.setFundingAttemptedAt("card-$index", NOW)
            }

            // Every mutation is a read-modify-write over the whole list. Without the mutex these
            // interleave at their suspension points and all but the last write vanishes.
            (0 until CONCURRENT).map { index -> async { storage.markShared("card-$index", NOW) } }.awaitAll()

            assertEquals(CONCURRENT, storage.getAll().size)
            assertEquals((0 until CONCURRENT).map { "card-$it" }.toSet(), storage.getAll().map { it.id }.toSet())
            assertTrue(storage.getAll().all { it.status == GiftCardStatus.SHARED })
        }

    @Test
    fun `minting supersedes an abandoned draft rather than stacking one up`() =
        runTest {
            val storage = storage()
            storage.add(card(id = "abandoned"))

            storage.add(card(id = "minted"))

            // Nothing was ever sent to the first, so its seed unlocks nothing; keeping it only
            // grows the one blob every mutation rewrites.
            assertEquals(listOf("minted"), storage.getAll().map { it.id })
        }

    @Test
    fun `refuses to read a corrupt blob as an empty list`() =
        runTest {
            val preferences = InMemoryPreferenceProvider()
            preferences.putString(PreferenceKey("gift_cards_v1"), "{not json")

            // Absent means "no cards"; undecodable must not, or the next add would replace a list
            // of funded cards with a list of one.
            assertFailsWith<StoreCorruptedException> { storage(preferences).getAll() }
            assertFailsWith<StoreCorruptedException> { storage(preferences).add(card()) }
        }

    @Test
    fun `refuses a record carrying a field this build does not know`() =
        runTest {
            val preferences = InMemoryPreferenceProvider()
            preferences.putString(PreferenceKey("gift_cards_v1"), NEWER_SCHEMA_BLOB)

            // Tolerating the unknown field would decode the record without it and drop it on the
            // next write — an older build silently deleting part of the only copy of a card's
            // recovery data. Refusing costs nothing: a mutation that cannot read does not write.
            assertFailsWith<StoreCorruptedException> { storage(preferences).getAll() }
            assertEquals(NEWER_SCHEMA_BLOB, preferences.getString(PreferenceKey("gift_cards_v1")))
        }

    @Test
    fun `keeps a corrupt blob rather than overwriting it`() =
        runTest {
            val preferences = InMemoryPreferenceProvider()
            preferences.putString(PreferenceKey("gift_cards_v1"), "{not json")

            runCatching { storage(preferences).add(card()) }

            assertEquals("{not json", preferences.getString(PreferenceKey("gift_cards_v1")))
        }

    private fun storage(preferences: PreferenceProvider = InMemoryPreferenceProvider()): GiftCardStorageProvider =
        GiftCardStorageProviderImpl(
            mockk<EncryptedPreferenceProvider>().also { coEvery { it.invoke() } returns preferences }
        )

    private fun card(id: String = ID) =
        StoredGiftCard(
            id = id,
            network = "main",
            address = "u1exampleunifiedaddressforgiftcardtests",
            mnemonic = MNEMONIC,
            amountZatoshi = 100_000_000L,
            birthdayHeight = 2_800_000L,
            sourceAccountUuid = ACCOUNT,
            createdAt = NOW,
            updatedAt = NOW,
            status = GiftCardStatus.DRAFT,
        )

    /**
     * Yields inside every suspending call so mutations genuinely interleave under `runTest` — a
     * fake that never suspends would let the concurrency test pass without a mutex.
     */
    private class InMemoryPreferenceProvider : PreferenceProvider {
        private val values = mutableMapOf<String, MutableStateFlow<String?>>()

        private fun flowFor(key: PreferenceKey) = values.getOrPut(key.key) { MutableStateFlow(null) }

        override suspend fun hasKey(key: PreferenceKey): Boolean = flowFor(key).value != null

        override suspend fun putString(key: PreferenceKey, value: String?) {
            yield()
            flowFor(key).value = value
        }

        override suspend fun getString(key: PreferenceKey): String? {
            yield()
            return flowFor(key).value
        }

        override fun observe(key: PreferenceKey): Flow<String?> = flowFor(key)

        override suspend fun remove(key: PreferenceKey) {
            flowFor(key).value = null
        }

        override suspend fun putStringSet(key: PreferenceKey, value: Set<String>?) = error("Unused")

        override suspend fun putLong(key: PreferenceKey, value: Long?) = error("Unused")

        override suspend fun getLong(key: PreferenceKey): Long = error("Unused")

        override suspend fun getStringSet(key: PreferenceKey): Set<String> = error("Unused")

        override suspend fun clearPreferences(): Boolean = error("Unused")
    }

    private companion object {
        const val ID = "card-1"
        const val ACCOUNT = "account-1"
        const val TXID = "f00d"
        const val NOW = "2026-08-20T12:00:00Z"
        const val CONCURRENT = 25

        /** A valid card as some later build would write it, plus one field this one has never seen. */
        val NEWER_SCHEMA_BLOB =
            """
            [{"id":"$ID","network":"main","address":"u1example","mnemonic":"$MNEMONIC",
            "amountZatoshi":100000000,"birthdayHeight":2800000,"sourceAccountUuid":"$ACCOUNT",
            "createdAt":"$NOW","updatedAt":"$NOW","status":"DRAFT","reclaimedAt":"$NOW"}]
            """.trimIndent()

        /** BIP-39 test vector for all-zero entropy. Never a real wallet. */
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon art"
    }
}
