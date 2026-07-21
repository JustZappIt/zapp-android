package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey

/**
 * Tracks whether the user has locked the dust balance left behind in Orchard after migration, so
 * Migration Complete shows the "locked" confirmation instead of the "lock balance" prompt on
 * re-entry. Backed by regular (non-encrypted) app storage, wiped on uninstall.
 */
interface HasLockedOrchardDustStorageProvider : BooleanStorageProvider

class HasLockedOrchardDustStorageProviderImpl(
    override val preferenceHolder: StandardPreferenceProvider,
) : BaseBooleanStorageProvider(key = PreferenceKey("has_locked_orchard_dust")),
    HasLockedOrchardDustStorageProvider
