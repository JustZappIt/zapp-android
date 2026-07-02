package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey

/**
 * Null = no active post-broadcast privacy buffer. Non-null = "don't let migration sync resume
 * before this instant" — set by the immediate ("send now") migration transfer path so the
 * SDK's [cash.z.ecc.android.sdk.OrchardMigrationSdk.isSyncBlocked] can decouple broadcast timing
 * from sync-resume timing for privacy. Encrypted, same as [co.electriccoin.zcash.ui.common.repository.MigrationPlanRepository],
 * since it's meaningless without the migration context it's paired with.
 */
interface MigrationSyncResumeAtStorageProvider : TimestampStorageProvider

class MigrationSyncResumeAtStorageProviderImpl(
    override val preferenceHolder: EncryptedPreferenceProvider,
) : BaseTimestampStorageProvider(key = PreferenceKey("migration_sync_resume_at")),
    MigrationSyncResumeAtStorageProvider
