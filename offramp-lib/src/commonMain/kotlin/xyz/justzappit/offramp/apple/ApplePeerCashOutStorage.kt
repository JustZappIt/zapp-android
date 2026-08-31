// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.justzappit.offramp.peer.PayeeHandle
import xyz.justzappit.offramp.peer.PayeeHash
import xyz.justzappit.offramp.peer.PeerCashOutCheckpoint
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.PeerResumeAction

/** A decode, validation, or host-I/O failure at the Peer recovery authority. */
internal class ApplePeerRecoveryStorageException(
    message: String,
    cause: Exception? = null,
) : Exception(message, cause)

/**
 * The two encrypted blobs the Peer rail owns on iOS. The host stores opaque JSON and knows nothing
 * about its shape: the book structure, its serialization and the locking that keeps concurrent
 * attempts from overwriting each other all live on this side, so there is one implementation of
 * them rather than one per platform.
 *
 * They are deliberately separate keys. The checkpoint book is recovery data for money already in
 * flight; the payee book holds raw handles, which are PII. Nothing may ever copy a value from the
 * second into the first.
 */
interface ApplePeerCashOutStorage {
    @Throws(Exception::class)
    fun peerCheckpointBookJson(): AppleStorageValue

    @Throws(Exception::class)
    fun storePeerCheckpointBookJson(value: String)

    @Throws(Exception::class)
    fun peerPayeeBookJson(): AppleStorageValue

    @Throws(Exception::class)
    fun storePeerPayeeBookJson(value: String)
}

/**
 * The unfinished cash-outs, keyed by attempt. Several can be in flight at once and each is cleared
 * as soon as its deposit is indexed, after which that order is recoverable from the chain alone.
 *
 * Every write is a read-modify-write of one blob holding every attempt, so it is serialised here: a
 * lost write is a lost recovery record for USDC that has already been broadcast.
 */
internal class ApplePeerCheckpointBook(
    private val storage: ApplePeerCashOutStorage,
) {
    private val mutex = Mutex()

    suspend fun all(): List<PeerCashOutCheckpoint> {
        val value =
            try {
                storage.peerCheckpointBookJson().value
            } catch (error: Exception) {
                throw ApplePeerRecoveryStorageException("The saved Peer cash-out records could not be read.", error)
            }
        return value?.let { decode(it).entries }.orEmpty()
    }

    suspend fun get(id: PeerCashOutId): PeerCashOutCheckpoint? = all().firstOrNull { it.id == id }

    suspend fun store(checkpoint: PeerCashOutCheckpoint) =
        mutex.withLock {
            write(all().filterNot { it.id == checkpoint.id } + checkpoint)
        }

    suspend fun clear(id: PeerCashOutId) =
        mutex.withLock {
            write(all().filterNot { it.id == id })
        }

    private fun write(entries: List<PeerCashOutCheckpoint>) {
        entries.forEach(::validateRecoverable)
        try {
            storage.storePeerCheckpointBookJson(
                JSON.encodeToString(Book.serializer(), Book(entries)),
            )
        } catch (error: Exception) {
            throw ApplePeerRecoveryStorageException("The saved Peer cash-out records could not be written.", error)
        }
    }

    /**
     * A book this build cannot read is still the recovery authority for funds that may already have
     * settled, so a decode failure surfaces rather than reading as "no attempts": the latter would
     * let the next cash-out spend USDC an unresolved attempt has already promised, and overwrite the
     * record of it on the next write.
     */
    private fun decode(value: String): Book =
        try {
            JSON.decodeFromString(Book.serializer(), value).also { book ->
                book.entries.forEach(::validateRecoverable)
            }
        } catch (error: ApplePeerRecoveryStorageException) {
            throw error
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            throw ApplePeerRecoveryStorageException(
                "The saved Peer cash-out records are incompatible or corrupted. Their recovery data was preserved.",
                error,
            )
        }

    /**
     * iOS has never initiated Peer's legacy bridge path. A record that would approve and send from
     * scratch is not recovery at all, while a legacy fuzzy marker has no safe order identity. All
     * three fail closed instead of turning unauthenticated local JSON into a new deposit.
     */
    private fun validateRecoverable(checkpoint: PeerCashOutCheckpoint) {
        when (val action = checkpoint.resumeAction) {
            is PeerResumeAction.ReadOrder,
            is PeerResumeAction.ResolveSubmittedDeposit,
            -> {
                Unit
            }

            is PeerResumeAction.ReconcileSubmission -> {
                if (action.submissionHash == null) {
                    throw ApplePeerRecoveryStorageException(
                        "The saved Peer cash-out record predates exact submission recovery.",
                    )
                }
            }

            is PeerResumeAction.ResumeBridge -> {
                throw ApplePeerRecoveryStorageException("A Peer bridge record is not recoverable on Apple platforms.")
            }

            PeerResumeAction.FreshStart -> {
                throw ApplePeerRecoveryStorageException("A fresh Peer request is not a recovery record.")
            }
        }
    }

    @Serializable
    private data class Book(
        val entries: List<PeerCashOutCheckpoint> = emptyList(),
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The user's payee per rail, so a revtag and a Zelle address can be held at once and registration is
 * not repeated on every order.
 *
 * [PayeeHash] is null until the curator has registered that exact handle. The pairing is what makes
 * the hash reusable: editing the handle invalidates it, and reusing a hash registered for a
 * different handle funds a deposit that pays somebody else.
 */
internal class ApplePeerPayeeBook(
    private val storage: ApplePeerCashOutStorage,
) {
    private val mutex = Mutex()

    suspend fun get(platform: PeerPlatform): ApplePeerPayeeRecord? =
        all()[platform.wireName]?.let { entry ->
            val handle = runCatching { platform.normalizeHandle(entry.handle) }.getOrNull() ?: return@let null
            ApplePeerPayeeRecord(handle = handle, hash = entry.hashHex?.let(PayeeHash::parseOrNull))
        }

    suspend fun store(platform: PeerPlatform, handle: PayeeHandle, hash: PayeeHash?) =
        mutex.withLock {
            val entry = Entry(handle = handle.value, hashHex = hash?.hex)
            storage.storePeerPayeeBookJson(
                JSON.encodeToString(Book.serializer(), Book(all() + (platform.wireName to entry))),
            )
        }

    /** An unreadable payee book only costs the user a re-registration, so it fails soft. */
    private suspend fun all(): Map<String, Entry> =
        storage
            .peerPayeeBookJson()
            .value
            ?.let { runCatching { JSON.decodeFromString(Book.serializer(), it) }.getOrNull() }
            ?.entries
            .orEmpty()

    @Serializable
    private data class Book(
        val entries: Map<String, Entry> = emptyMap(),
    )

    @Serializable
    private data class Entry(
        val handle: String,
        val hashHex: String? = null,
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

internal data class ApplePeerPayeeRecord(
    val handle: PayeeHandle,
    val hash: PayeeHash?,
)
