@file:Suppress("DEPRECATION")

package co.electriccoin.zcash.preference

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceWriteFailedException
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.spackle.Twig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.CharConversionException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.BadPaddingException

/**
 * Provides an Android implementation of shared preferences.
 *
 * This class is thread-safe.
 *
 * For a given preference file, it is expected that only a single instance is constructed and that
 * this instance lives for the lifetime of the application. Constructing multiple instances will
 * potentially corrupt preference data and will leak resources.
 *
 * @param dispatcher a serial dispatcher (parallelism of one) owning all access to
 * [sharedPreferences]; EncryptedSharedPreferences are not thread-safe, so every operation is
 * confined to it.
 */
class AndroidPreferenceProvider(
    private val sharedPreferences: SharedPreferences,
    private val dispatcher: CoroutineDispatcher
) : PreferenceProvider {
    private val clearPipeline = MutableSharedFlow<Unit>()

    private val mutex = Mutex()

    override suspend fun hasKey(key: PreferenceKey) =
        withContext(dispatcher) {
            sharedPreferences.contains(key.key)
        }

    @SuppressLint("ApplySharedPref")
    override suspend fun putString(
        key: PreferenceKey,
        value: String?
    ) = withContext(dispatcher) {
        mutex.withLock {
            val editor = sharedPreferences.edit()

            editor.putString(key.key, value)

            // commit() updates the in-memory map whether or not the disk write lands, so a caller
            // that writes and reads back to prove durability would be reassured by a failed write.
            // Callers store money-critical material here; a dropped write has to be loud.
            if (!editor.commit()) throw PreferenceWriteFailedException(key)
        }
    }

    @SuppressLint("ApplySharedPref")
    override suspend fun putStringSet(
        key: PreferenceKey,
        value: Set<String>?
    ) = withContext(dispatcher) {
        mutex.withLock {
            val editor = sharedPreferences.edit()

            editor.putStringSet(key.key, value)

            if (!editor.commit()) throw PreferenceWriteFailedException(key)
        }
    }

    @SuppressLint("ApplySharedPref")
    override suspend fun putLong(
        key: PreferenceKey,
        value: Long?
    ) = withContext(dispatcher) {
        mutex.withLock {
            val editor = sharedPreferences.edit()

            if (value != null) {
                editor.putLong(key.key, value)
            } else {
                editor.remove(key.key)
            }
            if (!editor.commit()) throw PreferenceWriteFailedException(key)
        }
    }

    override suspend fun getLong(key: PreferenceKey): Long? =
        withContext(dispatcher) {
            if (sharedPreferences.contains(key.key)) {
                sharedPreferences.getLong(key.key, 0)
            } else {
                null
            }
        }

    override suspend fun getString(key: PreferenceKey) =
        withContext(dispatcher) {
            sharedPreferences.getString(key.key, null)
        }

    override suspend fun getStringSet(key: PreferenceKey): Set<String>? =
        withContext(dispatcher) {
            sharedPreferences.getStringSet(key.key, null)
        }

    @SuppressLint("ApplySharedPref")
    override suspend fun clearPreferences() =
        withContext(dispatcher) {
            val editor = sharedPreferences.edit()

            editor.clear()

            clearPipeline.emit(Unit)

            return@withContext editor.commit()
        }

    override fun observe(key: PreferenceKey): Flow<String?> =
        callbackFlow {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    // Callback on main thread
                    trySend(Unit)
                }
            sharedPreferences.registerOnSharedPreferenceChangeListener(listener)

            this.launch {
                clearPipeline.collect {
                    send(Unit)
                }
            }

            // Kickstart the emissions
            trySend(Unit)

            awaitClose {
                sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }.flowOn(dispatcher)
            .map { getString(key) }

    @SuppressLint("ApplySharedPref")
    override suspend fun remove(key: PreferenceKey) {
        withContext(dispatcher) {
            val editor = sharedPreferences.edit()

            editor.remove(key.key)

            editor.commit()
        }
    }

    companion object Factory : AndroidPreferenceFactory by AndroidPreferenceFactoryImpl()
}

interface AndroidPreferenceFactory {
    suspend fun newStandard(context: Context, filename: String): PreferenceProvider

    suspend fun newEncrypted(context: Context, filename: String): PreferenceProvider
}

/**
 * Each created [AndroidPreferenceProvider] serializes its preference access on a dedicated
 * [Dispatchers.IO] parallelism-1 view, so at most one instance per filename must ever be
 * constructed (two instances would serialize independently); that invariant is what
 * [standardCache] and [encryptedCache] enforce. A view holds no thread of its own, so a failed
 * creation attempt leaks nothing.
 */
private class AndroidPreferenceFactoryImpl : AndroidPreferenceFactory {
    private val standardCache = PreferenceProviderCache()
    private val encryptedCache = PreferenceProviderCache()

    override suspend fun newStandard(context: Context, filename: String): PreferenceProvider =
        standardCache.getOrCreate(filename) {
            val dispatcher = Dispatchers.IO.limitedParallelism(1)

            val sharedPreferences =
                withContext(dispatcher) {
                    context.getSharedPreferences(filename, Context.MODE_PRIVATE)
                }

            AndroidPreferenceProvider(sharedPreferences, dispatcher)
        }

    /**
     * Android Keystore keys are hardware-bound and not transferred during device-to-device
     * migration, so the encrypted prefs file arrives on the new device but cannot be decrypted.
     * Only failures that provably mean the stored data can never be decrypted again trigger
     * [deleteCorruptedEncryptedPreferences] and a fresh start: the master key being verifiably
     * absent while the file exists ([isEncryptedFileOrphaned]), or a deterministic decryption or
     * keyset-parse failure ([isUnrecoverableCorruption]). Every other failure — for example a
     * transient Keystore outage — is logged and rethrown, because misclassifying it as corruption
     * would irreversibly destroy the stored secrets.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun newEncrypted(context: Context, filename: String): PreferenceProvider =
        encryptedCache.getOrCreate(filename) {
            val dispatcher = Dispatchers.IO.limitedParallelism(1)

            val sharedPreferences =
                withContext(dispatcher) {
                    val isOrphaned = isEncryptedFileOrphaned(context, filename)
                    try {
                        createEncryptedSharedPreferences(context, filename)
                    } catch (e: Exception) {
                        if (isOrphaned || isUnrecoverableCorruption(e)) {
                            Twig.error(e) {
                                "Encrypted preferences $filename can never be decrypted again; recreating them"
                            }
                            deleteCorruptedEncryptedPreferences(context, filename, e)
                            createEncryptedSharedPreferences(context, filename)
                        } else {
                            Twig.error(e) {
                                "Opening encrypted preferences $filename failed; keeping data intact for a retry"
                            }
                            throw e
                        }
                    }
                }

            AndroidPreferenceProvider(sharedPreferences, dispatcher)
        }

    /**
     * The device-to-device migration signature: the encrypted preferences file exists, but a
     * working Keystore definitively reports the master key absent, so nothing can ever decrypt
     * the file. The query is retried once so that a momentary Keystore hiccup does not disguise
     * a genuinely orphaned file as healthy; a Keystore that still cannot be queried yields false —
     * an unknown Keystore state must never authorize deleting the stored secrets.
     *
     * Must be evaluated before attempting [createEncryptedSharedPreferences]: a failed attempt has
     * already recreated the master key alias via [MasterKey.Builder.build], so querying the
     * Keystore afterwards would always report the alias present and never detect the orphan.
     */
    private fun isEncryptedFileOrphaned(
        context: Context,
        filename: String
    ): Boolean {
        if (!encryptedPreferencesFile(sharedPreferencesDirectory(context), filename).exists()) {
            return false
        }
        return retryOnceOrDefault(false) {
            !androidKeyStore().containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }

    private fun createEncryptedSharedPreferences(
        context: Context,
        filename: String
    ): SharedPreferences {
        val mainKey =
            MasterKey
                .Builder(context)
                .apply { setKeyScheme(MasterKey.KeyScheme.AES256_GCM) }
                .build()
        return EncryptedSharedPreferences.create(
            context,
            filename,
            mainKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * The in-memory `SharedPreferences` cache is cleared first so the retry gets a clean instance
     * instead of the cached corrupted one. `<filename>.xml` and its `.xml.bak` sibling are then
     * both deleted: `SharedPreferencesImpl.loadFromDisk` restores a leftover `.bak` over a fresh
     * file on the next process start, which would resurrect the corrupted data.
     *
     * The Keystore master-key alias goes last, and only for [isMasterKeyFailure]: a data-level
     * failure keeps the key, so the ciphertext stays decryptable by this device if the
     * classification was wrong, and the SDK's own encrypted store, which shares the alias, stays
     * readable. Files that could not be removed keep the key too, since deleting it under
     * ciphertext still on disk is what would strand the data for good.
     */
    private fun deleteCorruptedEncryptedPreferences(
        context: Context,
        filename: String,
        cause: Exception
    ) {
        runCatching {
            context
                .getSharedPreferences(filename, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }

        val isDeleted =
            runCatching { deleteEncryptedPreferencesFiles(sharedPreferencesDirectory(context), filename) }
                .getOrDefault(false)
        if (!isDeleted) {
            Twig.error { "Encrypted preferences $filename could not be deleted; keeping the master key" }
            return
        }

        if (isMasterKeyFailure(cause)) {
            runCatching { androidKeyStore().deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS) }
        }
    }
}

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val CAUSE_CHAIN_LIMIT = 10

private fun androidKeyStore(): KeyStore =
    KeyStore
        .getInstance(ANDROID_KEYSTORE)
        .apply { load(null) }

private fun causeChain(exception: Exception): List<Throwable> =
    generateSequence<Throwable>(exception) { it.cause }
        .take(CAUSE_CHAIN_LIMIT)
        .toList()

private const val ANDROID_KEY_STORE_EXCEPTION_SIMPLE_NAME = "KeyStoreException"

/**
 * `android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST`; AOSP maps both
 * `ResponseCode.KEY_NOT_FOUND` and `ResponseCode.KEY_PERMANENTLY_INVALIDATED` onto it.
 */
private const val ANDROID_KEY_STORE_ERROR_KEY_DOES_NOT_EXIST = 6

/** `android.security.KeyStoreException.ERROR_KEY_CORRUPTED`: the stored key blob no longer parses. */
private const val ANDROID_KEY_STORE_ERROR_KEY_CORRUPTED = 7

private val PERMANENT_ANDROID_KEY_STORE_ERROR_CODES =
    setOf(
        ANDROID_KEY_STORE_ERROR_KEY_DOES_NOT_EXIST,
        ANDROID_KEY_STORE_ERROR_KEY_CORRUPTED
    )

/**
 * AOSP's hard-coded wording for a key this device can never use again, from `KeymasterDefs` and
 * the `ResponseCode` switch in `KeyStore2.getKeyStoreException` (identical below API 31). The typed
 * classification read below arrived only in API 33, so this is the one permanence signal available
 * on every supported API level.
 */
private val PERMANENT_ANDROID_KEY_STORE_MESSAGES =
    setOf(
        "Signature/MAC verification failed",
        "Invalid key blob",
        "Key blob corrupted",
        "Key not found",
        "Key permanently invalidated"
    )

/** `android.security.KeyStoreException.isTransientFailure()`, API 33 and up; false where absent. */
private fun isTransientPerAndroidKeyStore(throwable: Throwable): Boolean =
    runCatching {
        throwable.javaClass.getMethod("isTransientFailure").invoke(throwable) as Boolean
    }.getOrDefault(false)

/** `android.security.KeyStoreException.getNumericErrorCode()`, API 33 and up; false where absent. */
private fun hasPermanentAndroidKeyStoreErrorCode(throwable: Throwable): Boolean {
    val numericErrorCode =
        runCatching {
            throwable.javaClass.getMethod("getNumericErrorCode").invoke(throwable) as Int
        }.getOrNull()

    return numericErrorCode != null && numericErrorCode in PERMANENT_ANDROID_KEY_STORE_ERROR_CODES
}

/**
 * True only when the Keystore itself reports a key that is gone, unparseable, or unable to
 * authenticate the stored ciphertext — the shape a device-to-device transfer leaves behind. A
 * failure AOSP cannot classify stays not-permanent: unsure means the veto holds and the store is
 * rethrown for a later attempt, never deleted.
 */
private fun isPermanentAndroidKeyStoreFailure(throwable: Throwable): Boolean {
    if (isTransientPerAndroidKeyStore(throwable)) return false

    val message = throwable.message.orEmpty()

    return PERMANENT_ANDROID_KEY_STORE_MESSAGES.any { message.startsWith(it) } ||
        hasPermanentAndroidKeyStoreErrorCode(throwable)
}

/**
 * True when [chain] carries an `android.security.KeyStoreException` that is not positively
 * permanent. That class extends `java.lang.Exception`, not [java.security.KeyStoreException], and
 * is matched by simple name because the framework class is not a JVM dependency of this module.
 * AOSP wraps a wedged Keystore daemon as `InvalidKeyException("Keystore operation failed")`
 * carrying it. Both classifiers veto on this, so a transient shape can be acted upon by neither.
 */
private fun hasTransientAndroidKeyStoreMarker(chain: List<Throwable>): Boolean =
    chain.any {
        it !is KeyStoreException &&
            it.javaClass.simpleName == ANDROID_KEY_STORE_EXCEPTION_SIMPLE_NAME &&
            !isPermanentAndroidKeyStoreFailure(it)
    }

/**
 * True only for failures that are deterministic for the stored ciphertext or this device's
 * Keystore: an AEAD/padding authentication failure, a Tink keyset that no longer decodes
 * ([CharConversionException] is Tink's malformed-hex signature, InvalidProtocolBufferException its
 * malformed-proto one), Tink's Keystore self-test failure ([KeyStoreException], thrown by
 * `AndroidKeystoreKmsClient.validateAead()` when an AEAD round-trip of a random message doesn't
 * match — a permanent condition on devices with a buggy hardware Keystore), or
 * [InvalidKeyException], the second line of defense for a device-to-device-orphaned file when
 * [isEncryptedFileOrphaned]'s Keystore query itself failed twice (see [retryOnceOrDefault]).
 * Other Keystore and general IO failures are excluded because they can be transient.
 *
 * A chain carrying the [hasTransientAndroidKeyStoreMarker] shape is never corruption, whatever
 * else it holds: deleting the store over it destroys the seed, while rethrowing leaves the store
 * readable again once the Keystore settles.
 */
internal fun isUnrecoverableCorruption(exception: Exception): Boolean {
    val chain = causeChain(exception)
    if (hasTransientAndroidKeyStoreMarker(chain)) return false
    return chain.any {
        it is BadPaddingException ||
            it is CharConversionException ||
            it is KeyStoreException ||
            it is InvalidKeyException ||
            it.javaClass.simpleName == "InvalidProtocolBufferException"
    }
}

/**
 * True for failures that mean the Keystore key itself is unusable, as opposed to the stored
 * ciphertext being unreadable or a transient Keystore-daemon hiccup. A chain carrying the
 * [hasTransientAndroidKeyStoreMarker] shape is never a master-key failure, or a transient error
 * would cost the master key shared with the SDK's own encrypted store and permanently orphan both.
 */
internal fun isMasterKeyFailure(exception: Exception): Boolean {
    val chain = causeChain(exception)
    if (hasTransientAndroidKeyStoreMarker(chain)) return false
    return chain.any { it is KeyStoreException || it is InvalidKeyException }
}

/**
 * Runs [block], retrying once if it throws; returns [default] when both attempts throw.
 */
internal fun <T> retryOnceOrDefault(
    default: T,
    block: () -> T
): T =
    runCatching(block)
        .recoverCatching { block() }
        .getOrDefault(default)
