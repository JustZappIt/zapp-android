package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey

/**
 * Migration-scoped Tor setting, distinct from the app's global [IsTorEnabledStorageProvider].
 * Defaults to `true` (privacy-by-default, per the migration Tor prompt's own default) and is only
 * ever written by the migration Tor prompt (`MigrationPrivacyVM`, on actual Confirm — not as a
 * side effect of toggling the checkbox) or by "Continue without Tor" on the Tor-failure recovery
 * sheet (`MigrationTorFailureVM`) — never by the app's regular Tor settings screen, and the app's
 * regular Tor settings screen never reads it either. Every migration broadcast site
 * (`MigrationSendingVM`, `MigrationWorker`, `MigrationKeystoneScanVM`) reads this instead of the
 * global provider. Backed by regular (non-encrypted) app storage, wiped on uninstall along with
 * the other migration flags.
 */
interface IsMigrationTorEnabledStorageProvider : BooleanStorageProvider

class IsMigrationTorEnabledStorageProviderImpl(
    override val preferenceHolder: StandardPreferenceProvider,
) : BaseBooleanStorageProvider(key = PreferenceKey("is_migration_tor_enabled"), default = true),
    IsMigrationTorEnabledStorageProvider
