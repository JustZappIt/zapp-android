package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.math.BigInteger

/**
 * Resumable snapshot of an in-flight "top up Base" bridge. Kept separate from [OfframpCheckpoint]
 * because a top-up has no order (no recipient UPI, no fiat) — reusing the order checkpoint would
 * force placeholder values past its `toRequest` validation. The deposit address is persisted the
 * instant the bridge opens so a process death re-polls the same 1-Click handle instead of opening a
 * second bridge and double-sending the user's ZEC.
 */
@Serializable
data class OfframpTopUpCheckpoint(
    val bridgeDepositAddress: String,
    val addUsdcMicroDecimal: String,
    val createdAtMillis: Long,
) {
    init {
        require(bridgeDepositAddress.isNotBlank()) { "bridgeDepositAddress must not be blank" }
        require(runCatching { BigInteger(addUsdcMicroDecimal) > BigInteger.ZERO }.getOrDefault(false)) {
            "OfframpTopUpCheckpoint.addUsdcMicroDecimal must be a positive micro amount, got '$addUsdcMicroDecimal'"
        }
    }
}

interface OfframpTopUpCheckpointStorageProvider {
    suspend fun get(): OfframpTopUpCheckpoint?

    suspend fun store(checkpoint: OfframpTopUpCheckpoint)

    suspend fun clear()

    fun observe(): Flow<OfframpTopUpCheckpoint?>
}

internal class OfframpTopUpCheckpointStorageProviderImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : OfframpTopUpCheckpointStorageProvider {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, OfframpTopUpCheckpoint.serializer())

    override suspend fun get(): OfframpTopUpCheckpoint? = store.get()

    override suspend fun store(checkpoint: OfframpTopUpCheckpoint) = store.set(checkpoint)

    override suspend fun clear() = store.clear()

    override fun observe(): Flow<OfframpTopUpCheckpoint?> = store.observe()

    companion object {
        private const val PREF_KEY = "offramp_topup_checkpoint_v1"
    }
}
