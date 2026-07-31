package co.electriccoin.zcash.ui.common.compose

import androidx.compose.runtime.saveable.Saver

/**
 * Persists an enum across process death by name rather than Java serialization. An unknown name
 * (a constant renamed or removed in a later build) restores to null, so rememberSaveable falls
 * back to its init default instead of crashing on the stale value.
 */
inline fun <reified T : Enum<T>> enumSaver(): Saver<T, String> =
    Saver(
        save = { it.name },
        restore = { name -> enumValues<T>().firstOrNull { it.name == name } },
    )
