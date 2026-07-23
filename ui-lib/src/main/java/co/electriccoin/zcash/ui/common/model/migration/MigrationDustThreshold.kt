package co.electriccoin.zcash.ui.common.model.migration

// TODO: replace with `OrchardMigrationSdk.migrationDustThresholdZatoshi()` (or however it ends up
// named) once the SDK exposes the real Rust-layer constant — this is a placeholder duplicating the
// documented ~0.001 ZEC `residual_after_migration()` threshold so the app has *a* real gate instead
// of the bare `> 0L`/`== MigrationState.Complete` checks that used to treat every round boundary as
// full completion. Every "is migration truly, fully complete" check in the app should compare
// against this constant (or its eventual SDK replacement), not define its own threshold.
const val MIGRATION_DUST_THRESHOLD_ZATOSHI = 100_000L
