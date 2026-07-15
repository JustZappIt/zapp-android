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
import co.electriccoin.zcash.ui.common.provider.SelectedAccountUUIDProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    selectedAccountUUIDProvider: SelectedAccountUUIDProvider,
    private val synchronizerProvider: SynchronizerProvider,
) : TransactionRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Outputs/recipients are only immutable once a transaction is fully enhanced (raw != null), so only enhanced txs
     * are cached; unenhanced txs are re-fetched via the batched queries on every emission — that re-fetch is what
     * picks up post-enhancement data.
     */
    private val enhancedTxCache = ConcurrentHashMap<String, TxDetails>()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val transactions: Flow<List<Transaction>?> =
        selectedAccountUUIDProvider
            .uuid
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

                                        // Refresh via batched queries whenever any tx is not yet cached,
                                        // which includes every unenhanced tx since those are never stored.
                                        val needsRefresh =
                                            transactions.any { transaction ->
                                                enhancedTxCache[transaction.txId.txIdString()] == null
                                            }
                                        val freshDetails =
                                            if (needsRefresh) {
                                                coroutineScope {
                                                    val outputsDeferred =
                                                        async { synchronizer.getTransactionOutputs() }
                                                    val recipientsDeferred = async { synchronizer.getRecipients() }
                                                    val batchedOutputs = outputsDeferred.await()
                                                    val batchedRecipients = recipientsDeferred.await()
                                                    transactions.associate { transaction ->
                                                        val details =
                                                            TxDetails(
                                                                outputs =
                                                                    batchedOutputs[transaction.txId].orEmpty(),
                                                                recipient =
                                                                    batchedRecipients[transaction.txId]
                                                                        ?.firstOrNull()
                                                                        ?.addressValue
                                                            )
                                                        val key = transaction.txId.txIdString()
                                                        if (transaction.raw != null) {
                                                            enhancedTxCache[key] = details
                                                        }
                                                        key to details
                                                    }
                                                }
                                            } else {
                                                emptyMap()
                                            }

                                        transactions
                                            .map { transaction ->
                                                val key = transaction.txId.txIdString()
                                                val details =
                                                    freshDetails[key]
                                                        ?: enhancedTxCache[key]
                                                        ?: TxDetails(outputs = emptyList(), recipient = null)
                                                createTransaction(transaction, details)
                                            }.sortedByDescending { transaction ->
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

    private fun createTransaction(transaction: TransactionOverview, details: TxDetails): Transaction =
        when (transaction.transactionState) {
            Expired -> {
                when {
                    transaction.isShielding -> {
                        ShieldTransaction.Failed(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = details.outputs,
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
                            transactionOutputs = details.outputs,
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = details.recipient
                        )
                    }

                    else -> {
                        ReceiveTransaction.Failed(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = details.outputs,
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
                            transactionOutputs = details.outputs,
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
                            transactionOutputs = details.outputs,
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = details.recipient
                        )
                    }

                    else -> {
                        ReceiveTransaction.Success(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = details.outputs,
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
                            transactionOutputs = details.outputs,
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
                            transactionOutputs = details.outputs,
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = details.recipient
                        )
                    }

                    else -> {
                        ReceiveTransaction.Pending(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = details.outputs,
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

private data class TxDetails(
    val outputs: List<TransactionOutput>,
    val recipient: String?,
)
