package co.electriccoin.zcash.preference

import android.content.SharedPreferences
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A clear reaches [AndroidPreferenceProvider.observe] through the provider's own pipeline, and whoever observes reads
 * the preference on that announcement: what it finds must be the cleared store. The fake below doesn't notify
 * listeners on a clear, as the platform preferences before Android 11 don't, so the pipeline is the only signal, as
 * it is there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPreferenceProviderClearTest {
    @Test
    fun clearIsCommittedBeforeItIsAnnounced() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val provider = AndroidPreferenceProvider(FakeSharedPreferences(), dispatcher)
            val key = PreferenceKey("seed")
            val seen = mutableListOf<String?>()
            val observing = provider.observe(key).onEach { seen += it }.launchIn(this)
            advanceUntilIdle()

            provider.putString(key, "abandon")
            advanceUntilIdle()
            assertEquals("abandon", seen.last())

            provider.clearPreferences()
            advanceUntilIdle()
            assertNull(seen.last(), "the announcement of a clear must read the cleared preference; saw $seen")
            assertNull(provider.getString(key))

            observing.cancel()
        }
    }
}

/** In-memory preferences whose edits land on commit, and which tell listeners about puts and removes only. */
private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(
        key: String?,
        defValue: String?
    ): String? = values[key] as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?
    ): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return values[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(
        key: String?,
        defValue: Int
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listener?.let { listeners += it }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listener?.let { listeners -= it }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val puts = mutableMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clears = false

        override fun putString(
            key: String?,
            value: String?
        ): SharedPreferences.Editor = apply { puts[requireNotNull(key)] = value }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?
        ): SharedPreferences.Editor = apply { puts[requireNotNull(key)] = values }

        override fun putInt(
            key: String?,
            value: Int
        ): SharedPreferences.Editor = apply { puts[requireNotNull(key)] = value }

        override fun putLong(
            key: String?,
            value: Long
        ): SharedPreferences.Editor = apply { puts[requireNotNull(key)] = value }

        override fun putFloat(
            key: String?,
            value: Float
        ): SharedPreferences.Editor = apply { puts[requireNotNull(key)] = value }

        override fun putBoolean(
            key: String?,
            value: Boolean
        ): SharedPreferences.Editor = apply { puts[requireNotNull(key)] = value }

        override fun remove(key: String?): SharedPreferences.Editor = apply { removes += requireNotNull(key) }

        override fun clear(): SharedPreferences.Editor = apply { clears = true }

        override fun commit(): Boolean {
            if (clears) values.clear()
            removes.forEach { values.remove(it) }
            puts.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            (removes + puts.keys).forEach { key ->
                listeners.toList().forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, key) }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
