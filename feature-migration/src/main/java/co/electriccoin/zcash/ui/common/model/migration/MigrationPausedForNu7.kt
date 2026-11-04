package co.electriccoin.zcash.ui.common.model.migration

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.LceContent
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.design.util.stringRes

/** Whether the SDK refused a plan, a commit or a note split for running into NU7 activation. */
fun Throwable.isMigrationPausedForNu7(): Boolean =
    generateSequence(this) { it.cause }.any { it.message?.startsWith(MIGRATION_PAUSED_FOR_NU7_MARKER) == true }

/** [ErrorMapperUseCase.mapToState], with its own title and message for that refusal. */
fun ErrorMapperUseCase.mapMigrationError(error: LceContent.Error) =
    if (error.cause.isMigrationPausedForNu7()) {
        mapToState(
            error,
            title = stringRes(R.string.ironwood_migration_paused_title),
            message = stringRes(R.string.ironwood_migration_paused_message),
        )
    } else {
        mapToState(error)
    }

// Mirrors MIGRATION_PAUSED_FOR_NU7_MARKER in the SDK's backend-lib migration.rs.
private const val MIGRATION_PAUSED_FOR_NU7_MARKER = "MigrationPausedForNu7"
