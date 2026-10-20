package co.electriccoin.zcash.ui.common.invest.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** A trade written before its funds move, so a restart still follows it to the end. */
@Serializable
data class InvestTradeCheckpoint(
    val depositAddress: String,
    val assetId: String,
    val createdAtMillis: Long,
    /** Sale only: when the signed intent stops being executable. */
    val intentDeadlineMillis: Long? = null,
    /** Sale only: the base units being sold. */
    val baseUnits: String? = null,
    /** Sale only: the private balance just before submitting, to tell whether the sale ran. */
    val heldBeforeBaseUnits: String? = null,
    /** Sale only: 1Click's hash of the submitted intent. */
    val intentHash: String? = null,
) {
    init {
        require(depositAddress.isNotBlank()) { "depositAddress must not be blank" }
    }
}

interface InvestTradeCheckpointStorageProvider {
    fun observe(): Flow<List<InvestTradeCheckpoint>>

    suspend fun add(checkpoint: InvestTradeCheckpoint)

    suspend fun remove(depositAddress: String)
}

interface InvestBuyCheckpointStorageProvider : InvestTradeCheckpointStorageProvider

interface InvestSellCheckpointStorageProvider : InvestTradeCheckpointStorageProvider

internal class InvestBuyCheckpointStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : InvestBuyCheckpointStorageProvider,
    InvestTradeCheckpointStorageProvider by InvestTradeCheckpointStore(
        encryptedPreferenceProvider,
        prefKey = "invest_buy_checkpoints_v1",
    )

internal class InvestSellCheckpointStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : InvestSellCheckpointStorageProvider,
    InvestTradeCheckpointStorageProvider by InvestTradeCheckpointStore(
        encryptedPreferenceProvider,
        prefKey = "invest_sell_checkpoints_v1",
    )

private class InvestTradeCheckpointStore(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
    prefKey: String,
) : InvestTradeCheckpointStorageProvider {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, prefKey, Checkpoints.serializer())
    private val mutex = Mutex()

    override fun observe(): Flow<List<InvestTradeCheckpoint>> = store.observe().map { it?.items.orEmpty() }

    override suspend fun add(checkpoint: InvestTradeCheckpoint) =
        mutex.withLock {
            val items =
                store
                    .get()
                    ?.items
                    .orEmpty()
                    .filterNot { it.depositAddress == checkpoint.depositAddress }
            store.set(Checkpoints(items + checkpoint))
        }

    override suspend fun remove(depositAddress: String) =
        mutex.withLock {
            val items = store.get()?.items.orEmpty()
            if (items.any { it.depositAddress == depositAddress }) {
                store.set(Checkpoints(items.filterNot { it.depositAddress == depositAddress }))
            }
        }

    @Serializable
    private data class Checkpoints(
        val items: List<InvestTradeCheckpoint>,
    )
}
