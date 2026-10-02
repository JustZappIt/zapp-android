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
import xyz.justzappit.railgun.RailgunSignedTransaction
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PrivateUsdSendRecord(
    val txHash: TxHash,
    val token: Address,
    /** Token base units taken from the private balance. */
    @Serializable(with = DecimalSerializer::class)
    val amount: BigInteger,
    val to: RailgunDestination,
    /** Unix seconds. */
    val sentAt: Long,
    /** False until it's seen in a block; earlier builds logged a send only once it was. */
    val confirmed: Boolean = true,
    /** What was signed, kept to send again until it's in a block. */
    val signed: PrivateUsdSignedSend? = null,
) {
    val withdraw: Boolean get() = to is RailgunDestination.Public
}

/** A signed transaction of the gas account's, as the log keeps it. */
@Serializable
data class PrivateUsdSignedSend(
    val raw: String,
    val from: Address,
    val nonce: Long,
) {
    override fun toString() = "PrivateUsdSignedSend(from=$from, nonce=$nonce)"
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

    fun signed(
        transaction: RailgunSignedTransaction,
        at: Long
    ) = PrivateUsdSendRecord(
        txHash = transaction.txHash,
        token = token,
        amount = amount,
        to = to,
        sentAt = at,
        confirmed = false,
        signed = PrivateUsdSignedSend(transaction.raw, transaction.from, transaction.nonce),
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

    // A send is signed and logged before it goes out, so one an earlier process left pending never did.
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

    /** Keeps [transaction] for [send] before it goes out; a send this fails for must not go. */
    suspend fun sign(
        send: PrivateUsdPendingSend,
        transaction: RailgunSignedTransaction,
        at: Long
    ) {
        live -= send.id
        change { log ->
            log.copy(
                sends = log.sends + send.signed(transaction, at),
                pending = log.pending.filter { it.id != send.id },
            )
        }
    }

    suspend fun confirm(txHash: TxHash) =
        change { log ->
            log.copy(
                sends = log.sends.map { if (it.txHash == txHash) it.copy(confirmed = true, signed = null) else it },
            )
        }

    /** Forgets [send], and its transaction if one was signed, once nothing moved for it. */
    suspend fun remove(
        send: PrivateUsdPendingSend,
        txHash: TxHash?
    ) {
        live -= send.id
        change { log ->
            log.copy(
                sends = log.sends.filterNot { txHash != null && it.txHash == txHash },
                pending = log.pending.filter { it.id != send.id },
            )
        }
    }

    /** Forgets the transaction [txHash], which moved nothing. */
    suspend fun remove(txHash: TxHash) = change { log -> log.copy(sends = log.sends.filterNot { it.txHash == txHash }) }

    private suspend fun change(update: (Log) -> Log) =
        lock.withLock {
            val log = read()
            val changed = update(log.copy(pending = log.pending.filter { it.id in live }))
            val retained =
                changed.sends.filterNot { it.confirmed } +
                    changed.sends.filter { it.confirmed }.takeLast(MAX_SENDS)
            store.set(changed.copy(sends = retained.sortedBy { it.sentAt }))
        }

    // This is also the recovery checkpoint: a failed read must never replace signed transactions.
    private suspend fun read(): Log = store.get() ?: Log()

    @Serializable
    private data class Log(
        val sends: List<PrivateUsdSendRecord> = emptyList(),
        val pending: List<PrivateUsdPendingSend> = emptyList(),
    )

    private val Log.isUnsettled: Boolean get() = sends.any { !it.confirmed } || pending.any { it.id in live }

    private companion object {
        const val PREF_KEY = "private_usd_sends_v1"
        const val MAX_SENDS = 100
    }
}
