package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryArgs
import co.electriccoin.zcash.ui.screen.migration.privacy.MigrationPrivacyArgs
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs

/**
 * The Tor sheet only has something to offer when Tor isn't already the user's global setting —
 * if it's already on, both migration entry points skip straight past it. What "past it" means
 * depends on mode: IMMEDIATE (called from Setup) goes straight to Confirm Transfer Plan, since
 * there's nothing else between Setup and Review for that path. AUTOMATIC (called from How This
 * Works, ahead of Battery/Notification) goes to the Battery screen next — asked there regardless
 * of the answer, since background delivery is scheduled unconditionally either way.
 */
class GetMigrationPrivacyOrReviewDestinationUseCase(
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
) {
    suspend operator fun invoke(mode: MigrationMode): Any {
        val torAlreadyOn = isTorEnabledStorageProvider.get() == true
        return when (mode) {
            MigrationMode.IMMEDIATE ->
                if (torAlreadyOn) MigrationReviewArgs(mode = mode) else MigrationPrivacyArgs(mode = mode)
            MigrationMode.AUTOMATIC ->
                if (torAlreadyOn) MigrationBatteryArgs else MigrationPrivacyArgs(mode = mode)
        }
    }
}
