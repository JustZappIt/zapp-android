// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.railgun.DecimalSerializer
import xyz.justzappit.railgun.RailgunDestination
import xyz.justzappit.railgun.RailgunNullifiers
import xyz.justzappit.railgun.RailgunRelayRequest
import xyz.justzappit.railgun.RailgunRelayedProof
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PrivateUsdSendRecord(
    val id: String,
    val token: Address,
    /** Token base units taken from the private balance, the broadcaster's fee aside. */
    @Serializable(with = DecimalSerializer::class)
    val amount: BigInteger,
    val to: RailgunDestination,
    /** Unix seconds. */
    val sentAt: Long,
    /** The broadcaster's transaction, once it named one. */
    val txHash: TxHash? = null,
    /** False until it's seen in a block, or its nullifiers are. */
    val confirmed: Boolean = true,
    /** What the broadcaster is asked to send, kept to ask again until it settles. */
    val relay: PrivateUsdRelay? = null,
) {
    val withdraw: Boolean get() = to is RailgunDestination.Public

    /** The broadcaster sent its request in [txHash]. */
    fun submitted(
        txHash: TxHash,
        at: Long
    ) = copy(txHash = txHash, relay = relay?.copy(postedAt = at, spentAt = null, spentIn = emptyList()))

    /** Its request went out without an answer that settles anything. */
    fun posted(at: Long) = copy(relay = relay?.copy(postedAt = at))

    /** The broadcaster answered that a note it spends is spent, by [transactions] of its own where it named any. */
    fun spent(
        at: Long,
        transactions: List<TxHash>
    ) = copy(relay = relay?.copy(postedAt = at, spentAt = at, spentIn = transactions))

    /** It landed, in [txHash] when that's known. */
    fun landed(txHash: TxHash?) = copy(confirmed = true, txHash = txHash, relay = null)
}

/** A proved send as the log keeps it until it settles, and when the broadcaster last answered about it. */
@Serializable
data class PrivateUsdRelay(
    val request: RailgunRelayRequest,
    val spends: List<RailgunNullifiers>,
    /** Unix seconds of the last post. */
    val postedAt: Long? = null,
    /** Unix seconds of the last answer that a note it spends is spent, which the chain then settles. */
    val spentAt: Long? = null,
    /** The broadcaster's own transactions that answer named as spending its notes. */
    val spentIn: List<TxHash> = emptyList(),
) {
    override fun toString() = "PrivateUsdRelay($request, postedAt=$postedAt, spentAt=$spentAt)"
}

/** A send still being proved, which nothing has left the wallet for. */
@Serializable
data class PrivateUsdPendingSend(
    val id: String,
    val token: Address,
    @Serializable(with = DecimalSerializer::class)
    val amount: BigInteger,
    val to: RailgunDestination,
    /** Unix seconds. */
    val startedAt: Long,
) {
    val withdraw: Boolean get() = to is RailgunDestination.Public

    fun proved(
        proof: RailgunRelayedProof,
        at: Long
    ) = PrivateUsdSendRecord(
        id = id,
        token = token,
        amount = amount,
        to = to,
        sentAt = at,
        confirmed = false,
        relay = PrivateUsdRelay(proof.request, proof.spends),
    )
}

data class PrivateUsdSendHistory(
    val sends: List<PrivateUsdSendRecord> = emptyList(),
    val pending: List<PrivateUsdPendingSend> = emptyList(),
) {
    val isEmpty: Boolean get() = sends.isEmpty() && pending.isEmpty()
}

/** The sends and withdrawals made from this device, oldest first, from before their proof to their block. */
class PrivateUsdSendLog(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, Log.serializer())
    private val lock = Mutex()

    // A send is proved and logged before it goes out, so one an earlier process left pending never did.
    private val live = ConcurrentHashMap.newKeySet<String>()

    val observe: Flow<PrivateUsdSendHistory> =
        store
            .observe()
            .map { log -> PrivateUsdSendHistory(log?.sends.orEmpty(), log?.pending.orEmpty().filter { it.id in live }) }
            .catch { e ->
                Twig.warn(e) { "Private USD: the send log is unreadable" }
                emit(PrivateUsdSendHistory())
            }

    suspend fun unconfirmed(): List<PrivateUsdSendRecord> = lock.withLock { read().sends.filterNot { it.confirmed } }

    /** Unlike the activity list, an unreadable log must keep further spending blocked. */
    val observeUnsettled: Flow<Boolean> = store.observe().map { it?.isUnsettled == true }

    suspend fun hasUnsettled(): Boolean = lock.withLock { read().isUnsettled }

    suspend fun begin(send: PrivateUsdPendingSend) {
        live += send.id
        change { it.copy(pending = it.pending + send) }
    }

    /** Keeps [proof] for [send] before it goes out; a send this fails for must not go. */
    suspend fun prove(
        send: PrivateUsdPendingSend,
        proof: RailgunRelayedProof,
        at: Long
    ) {
        live -= send.id
        change { log ->
            log.copy(
                sends = log.sends + send.proved(proof, at),
                pending = log.pending.filter { it.id != send.id },
            )
        }
    }

    /** Changes [id], if it's still kept. */
    suspend fun update(
        id: String,
        transform: (PrivateUsdSendRecord) -> PrivateUsdSendRecord
    ) = change { log -> log.copy(sends = log.sends.map { if (it.id == id) transform(it) else it }) }

    /** Forgets [send], which nothing was proved for. */
    suspend fun remove(send: PrivateUsdPendingSend) {
        live -= send.id
        change { log -> log.copy(pending = log.pending.filter { it.id != send.id }) }
    }

    /** Forgets [id], which moved nothing. */
    suspend fun remove(id: String) = change { log -> log.copy(sends = log.sends.filterNot { it.id == id }) }

    private suspend fun change(update: (Log) -> Log) =
        lock.withLock {
            val log = read()
            val changed = update(log.copy(pending = log.pending.filter { it.id in live }))
            val retained =
                changed.sends.filterNot { it.confirmed } +
                    changed.sends.filter { it.confirmed }.takeLast(MAX_SENDS)
            store.set(changed.copy(sends = retained.sortedBy { it.sentAt }))
        }

    // This is also the recovery checkpoint: a failed read must never replace proved requests.
    private suspend fun read(): Log = store.get() ?: Log()

    @Serializable
    private data class Log(
        val sends: List<PrivateUsdSendRecord> = emptyList(),
        val pending: List<PrivateUsdPendingSend> = emptyList(),
    )

    private val Log.isUnsettled: Boolean get() = sends.any { !it.confirmed } || pending.any { it.id in live }

    private companion object {
        const val PREF_KEY = "private_usd_sends_v2"
        const val MAX_SENDS = 100
    }
}
