package co.electriccoin.zcash.ui.common.pricing.provider

import android.content.Context
import android.util.AtomicFile
import cash.z.ecc.android.sdk.model.FiatCurrency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Locale

interface HistoricalPriceCacheProvider {
    suspend fun load(fiatCurrency: FiatCurrency): HistoricalPriceCache?

    suspend fun store(fiatCurrency: FiatCurrency, cache: HistoricalPriceCache)
}

class HistoricalPriceCacheProviderImpl(
    context: Context,
) : HistoricalPriceCacheProvider {
    private val cacheDirectory = context.noBackupFilesDir

    override suspend fun load(fiatCurrency: FiatCurrency): HistoricalPriceCache? =
        withContext(Dispatchers.IO) {
            deleteVersionedCacheFiles(fiatCurrency)
            val file = fileFor(fiatCurrency)
            try {
                if (!file.baseFile.exists()) return@withContext null
                JSON
                    .decodeFromString<HistoricalPriceCache>(file.readFully().decodeToString())
                    .takeIf {
                        it.schemaVersion == CACHE_SCHEMA_VERSION &&
                            it.fiatCurrencyCode == fiatCurrency.code
                    }
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    override suspend fun store(
        fiatCurrency: FiatCurrency,
        cache: HistoricalPriceCache,
    ) =
        withContext(Dispatchers.IO) {
            require(cache.fiatCurrencyCode == fiatCurrency.code)
            deleteVersionedCacheFiles(fiatCurrency)
            val file = fileFor(fiatCurrency)
            val output = file.startWrite()
            try {
                output.write(JSON.encodeToString(cache).encodeToByteArray())
                output.flush()
                file.finishWrite(output)
            } catch (e: IOException) {
                file.failWrite(output)
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException
            ) {
                file.failWrite(output)
                throw e
            }
        }

    private fun fileFor(fiatCurrency: FiatCurrency): AtomicFile {
        require(FiatCurrency.isAlpha3Code(fiatCurrency.code))
        val code = fiatCurrency.code.lowercase(Locale.ROOT)
        return AtomicFile(File(cacheDirectory, "historical_zec_${code}_prices.json"))
    }

    private fun deleteVersionedCacheFiles(fiatCurrency: FiatCurrency) {
        require(FiatCurrency.isAlpha3Code(fiatCurrency.code))
        val prefix = "historical_zec_${fiatCurrency.code.lowercase(Locale.ROOT)}_prices_v"
        cacheDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix)) file.delete()
        }
    }
}

@Serializable
data class HistoricalPriceCache(
    val schemaVersion: Int = CACHE_SCHEMA_VERSION,
    val fiatCurrencyCode: String,
    val availableFrom: String? = null,
    val availableTo: String? = null,
    val dataAsOf: String? = null,
    val points: List<CachedDailyPrice> = emptyList(),
    val completedRanges: List<CachedDateRange> = emptyList(),
    val lastCompletedRequestDate: String? = null,
    val unavailableCheckedAt: String? = null,
    val refreshNotBefore: String? = null,
)

@Serializable
data class CachedDailyPrice(
    val date: String,
    val fiatPerZec: String,
)

@Serializable
data class CachedDateRange(
    val from: String,
    val to: String,
)

const val CACHE_SCHEMA_VERSION = 2
private val JSON = Json { ignoreUnknownKeys = true }
