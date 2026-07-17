package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.screen.migration.privacy.MigrationPrivacyArgs
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs

/**
 * The Tor sheet only has something to offer when Tor isn't already the user's global setting —
 * if it's already on, both migration entry points (Setup's IMMEDIATE path, Notification's
 * AUTOMATIC path) skip straight to Confirm Transfer Plan instead of asking again.
 */
class GetMigrationPrivacyOrReviewDestinationUseCase(
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
) {
    suspend operator fun invoke(mode: MigrationMode, backgroundAvailable: Boolean): Any =
        if (isTorEnabledStorageProvider.get() == true) {
            MigrationReviewArgs(mode = mode, backgroundAvailable = backgroundAvailable)
        } else {
            MigrationPrivacyArgs(mode = mode, backgroundAvailable = backgroundAvailable)
        }
}
