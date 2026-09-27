package co.electriccoin.zcash.ui.common.invest.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * A buy whose ZEC may have left the wallet. Written before the send, so a process death between sending
 * and polling still finds the 1Click deposit address and resumes; removed once the buy is final.
 */
@Serializable
data class InvestBuyCheckpoint(
    val depositAddress: String,
    val assetId: String,
    val createdAtMillis: Long,
) {
    init {
        require(depositAddress.isNotBlank()) { "depositAddress must not be blank" }
    }
}

interface InvestBuyCheckpointStorageProvider {
    fun observe(): Flow<List<InvestBuyCheckpoint>>

    suspend fun add(checkpoint: InvestBuyCheckpoint)

    suspend fun remove(depositAddress: String)
}

internal class InvestBuyCheckpointStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : InvestBuyCheckpointStorageProvider {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, Checkpoints.serializer())
    private val mutex = Mutex()

    override fun observe(): Flow<List<InvestBuyCheckpoint>> = store.observe().map { it?.items.orEmpty() }

    override suspend fun add(checkpoint: InvestBuyCheckpoint) =
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
    internal data class Checkpoints(
        val items: List<InvestBuyCheckpoint>,
    )

    private companion object {
        const val PREF_KEY = "invest_buy_checkpoints_v1"
    }
}
