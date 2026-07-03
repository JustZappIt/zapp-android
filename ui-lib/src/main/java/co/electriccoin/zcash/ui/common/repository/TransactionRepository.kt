package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOutput
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.TransactionState.Confirmed
import cash.z.ecc.android.sdk.model.TransactionState.Expired
import cash.z.ecc.android.sdk.model.TransactionState.Pending
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

interface TransactionRepository {
    val transactions: Flow<List<Transaction>?>

    suspend fun getMemos(transaction: Transaction): List<String>

    fun observeTransaction(txId: String): Flow<Transaction?>

    fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?>

    suspend fun getTransactions(): List<Transaction>

    suspend fun resolveWalletAddress(address: String): WalletAddress?
}

class TransactionRepositoryImpl(
    accountDataSource: AccountDataSource,
    private val synchronizerProvider: SynchronizerProvider,
) : TransactionRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Transaction outputs are immutable once a transaction exists in the DB, so they are safe to
    // cache by txId and reused across flow re-emissions, avoiding redundant per-transaction SQLite
    // queries via [Synchronizer.getTransactionOutputs] on every sync status change or new block.
    private val transactionOutputsCache = ConcurrentHashMap<String, List<TransactionOutput>>()

    // First recipient address per txId, warmed via batched [Synchronizer.getRecipients]. A present key
    // with an empty Optional means "warmed, no address recipient" (e.g. shielded/receive txs), as opposed
    // to an absent key, which means "not warmed yet" and requires a per-tx fallback query.
    private val recipientAddressCache = ConcurrentHashMap<String, Optional<String>>()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val transactions: Flow<List<Transaction>?> =
        accountDataSource
            .selectedAccount
            .map { it?.sdkAccount?.accountUuid }
            .distinctUntilChanged()
            .flatMapLatest { uuid ->
                if (uuid == null) {
                    flowOf(null)
                } else {
                    synchronizerProvider
                        .synchronizer
                        .flatMapLatest { synchronizer ->
                            if (synchronizer == null) {
                                flowOf(null)
                            } else {
                                val normalizedTransactions =
                                    combine(
                                        synchronizer.getTransactions(uuid),
                                        synchronizer.status
                                    ) { transactions, status ->
                                        transactions.map {
                                            if (it.isSentTransaction) {
                                                it.copy(
                                                    transactionState =
                                                        createTransactionState(
                                                            minedHeight = it.minedHeight,
                                                            isSyncing = status == Synchronizer.Status.SYNCING
                                                        ) ?: it.transactionState
                                                )
                                            } else {
                                                it
                                            }
                                        }
                                    }.distinctUntilChanged()

                                normalizedTransactions
                                    .mapLatest { transactions ->
                                        val now = Instant.now()

                                        // Warm the outputs cache with a single batched query when any
                                        // transaction is not yet cached
                                        val hasUncached =
                                            transactions.any { transaction ->
                                                !transactionOutputsCache.containsKey(transaction.txId.txIdString())
                                            }
                                        if (hasUncached) {
                                            val batched = synchronizer.getTransactionOutputs()
                                            // Seed ALL current txs so change-only txs (absent from the
                                            // batched result) cache as empty instead of falling back to a
                                            // per-tx DB query on every cold start.
                                            transactions.forEach { transaction ->
                                                transactionOutputsCache[transaction.txId.txIdString()] =
                                                    batched[transaction.txId] ?: emptyList()
                                            }
                                        }

                                        // Warm the recipient address cache with a single batched query when
                                        // any transaction is not yet cached
                                        val hasUncachedRecipients =
                                            transactions.any { transaction ->
                                                !recipientAddressCache.containsKey(transaction.txId.txIdString())
                                            }
                                        if (hasUncachedRecipients) {
                                            val batchedRecipients = synchronizer.getRecipients()
                                            // Seed ALL current txs so absence-after-warm means "no address recipient"
                                            transactions.forEach { transaction ->
                                                val key = transaction.txId.txIdString()
                                                val address =
                                                    batchedRecipients[transaction.txId]
                                                        ?.firstOrNull()
                                                        ?.addressValue
                                                recipientAddressCache[key] = Optional.ofNullable(address)
                                            }
                                        }

                                        transactions
                                            .map { transaction ->
                                                createTransaction(transaction, synchronizer)
                                            }
                                            .sortedByDescending { transaction ->
                                                transaction.timestamp ?: now
                                            }
                                    }
                            }
                        }.onStart { emit(null) }
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.Lazily,
                initialValue = null
            )

    private suspend fun createTransaction(transaction: TransactionOverview, synchronizer: Synchronizer): Transaction =
        when (transaction.transactionState) {
            Expired -> {
                when {
                    transaction.isShielding -> {
                        ShieldTransaction.Failed(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = getCachedOutputs(transaction, synchronizer),
                            amount = transaction.totalSpent,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.netValue,
                            overview = transaction,
                            recipient = null
                        )
                    }

                    transaction.isSentTransaction -> {
                        SendTransaction.Failed(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = getCachedOutputs(transaction, synchronizer),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = getRecipient(transaction)
                        )
                    }

                    else -> {
                        ReceiveTransaction.Failed(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = getCachedOutputs(transaction, synchronizer),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            overview = transaction,
                            recipient = null
                        )
                    }
                }
            }

            Confirmed -> {
                when {
                    transaction.isShielding -> {
                        ShieldTransaction.Success(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = getCachedOutputs(transaction, synchronizer),
                            amount = transaction.totalSpent,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.netValue,
                            overview = transaction,
                            recipient = null
                        )
                    }

                    transaction.isSentTransaction -> {
                        SendTransaction.Success(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = getCachedOutputs(transaction, synchronizer),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = getRecipient(transaction)
                        )
                    }

                    else -> {
                        ReceiveTransaction.Success(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = getCachedOutputs(transaction, synchronizer),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            overview = transaction,
                            recipient = null
                        )
                    }
                }
            }

            Pending -> {
                when {
                    transaction.isShielding -> {
                        ShieldTransaction.Pending(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = getCachedOutputs(transaction, synchronizer),
                            amount = transaction.totalSpent,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.netValue,
                            overview = transaction,
                            recipient = null
                        )
                    }

                    transaction.isSentTransaction -> {
                        SendTransaction.Pending(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = getCachedOutputs(transaction, synchronizer),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = getRecipient(transaction)
                        )
                    }

                    else -> {
                        ReceiveTransaction.Pending(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = getCachedOutputs(transaction, synchronizer),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            overview = transaction,
                            recipient = null
                        )
                    }
                }
            }
        }

    private suspend fun getCachedOutputs(
        transaction: TransactionOverview,
        synchronizer: Synchronizer
    ): List<TransactionOutput> {
        val txId = transaction.txId.txIdString()
        val cached = transactionOutputsCache[txId]
        if (cached != null) {
            return cached
        }

        val outputs = synchronizer.getTransactionOutputs(transaction)
        transactionOutputsCache[txId] = outputs
        return outputs
    }

    private fun createTransactionState(minedHeight: BlockHeight?, isSyncing: Boolean): TransactionState? =
        when {
            minedHeight != null -> Confirmed
            isSyncing -> Pending
            else -> null
        }

    private fun createTimestamp(overview: TransactionOverview): Instant? =
        overview.blockTimeEpochSeconds?.let { Instant.ofEpochSecond(it) }

    override suspend fun getMemos(transaction: Transaction): List<String> =
        withContext(Dispatchers.IO) {
            synchronizerProvider
                .getSynchronizer()
                .getMemos(transaction.overview)
                .mapNotNull { memo -> memo.takeIf { it.isNotEmpty() } }
                .toList()
        }

    override fun observeTransaction(txId: String): Flow<Transaction?> =
        transactions
            .map { transactions ->
                transactions?.find { it.id.txIdString() == txId }
            }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> =
        synchronizerProvider
            .synchronizer
            .flatMapLatest { synchronizer ->
                synchronizer?.getTransactionsByMemoSubstring(memo)?.onEmpty { emit(listOf()) } ?: flowOf(null)
            }.distinctUntilChanged()

    override suspend fun getTransactions(): List<Transaction> = transactions.filterNotNull().first()

    private suspend fun getRecipient(overview: TransactionOverview): String? {
        val txId = overview.txId.txIdString()
        val cached = recipientAddressCache[txId]
        return if (cached != null) {
            // Present key: either a warmed address, or Optional.empty() meaning "no address recipient".
            // Either way, no fallback query is needed.
            cached.orElse(null)
        } else {
            // Absent key: not warmed yet, fall back to a per-tx query and cache the result.
            val fallbackAddress =
                synchronizerProvider
                    .getSynchronizer()
                    .getRecipients(overview)
                    .firstOrNull()
                    ?.addressValue
            recipientAddressCache[txId] = Optional.ofNullable(fallbackAddress)
            fallbackAddress
        }
    }

    override suspend fun resolveWalletAddress(address: String): WalletAddress? =
        when (synchronizerProvider.getSynchronizer().validateAddress(address)) {
            AddressType.Shielded -> WalletAddress.Sapling.new(address)
            AddressType.Tex -> WalletAddress.Tex.new(address)
            AddressType.Transparent -> WalletAddress.Transparent.new(address)
            AddressType.Unified -> WalletAddress.Unified.new(address)
            else -> null
        }
}

sealed interface Transaction {
    val id: TransactionId
    val amount: Zatoshi
    val memoCount: Int
    val timestamp: Instant?
    val transactionOutputs: List<TransactionOutput>
    val overview: TransactionOverview
    val fee: Zatoshi?
    val recipient: String?
}

sealed interface SendTransaction : Transaction {
    data class Success(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val fee: Zatoshi?,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: String?,
    ) : SendTransaction

    data class Pending(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val fee: Zatoshi?,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: String?,
    ) : SendTransaction

    data class Failed(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val fee: Zatoshi?,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: String?,
    ) : SendTransaction
}

sealed interface ReceiveTransaction : Transaction {
    override val fee: Zatoshi?
        get() = null

    data class Success(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: String?,
    ) : ReceiveTransaction

    data class Pending(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: String?,
    ) : ReceiveTransaction

    data class Failed(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: String?,
    ) : ReceiveTransaction
}

sealed interface ShieldTransaction : Transaction {
    data class Success(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val fee: Zatoshi?,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: String?,
    ) : ShieldTransaction

    data class Pending(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val fee: Zatoshi?,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: String?,
    ) : ShieldTransaction

    data class Failed(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val memoCount: Int,
        override val timestamp: Instant?,
        override val transactionOutputs: List<TransactionOutput>,
        override val fee: Zatoshi?,
        override val overview: TransactionOverview,
        override val recipient: String?,
    ) : ShieldTransaction
}

val Transaction.isPending: Boolean
    get() = this is SendTransaction.Pending || this is ShieldTransaction.Pending || this is ReceiveTransaction.Pending
