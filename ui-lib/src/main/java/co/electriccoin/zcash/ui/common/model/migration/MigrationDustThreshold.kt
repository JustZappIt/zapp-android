package co.electriccoin.zcash.ui.common.model.migration

// Callers should prefer the live `OrchardMigrationSdk.migrationDustThresholdZatoshi()` value —
// this constant exists only as the fallback for the rare case where no SDK/wallet is available yet
// (e.g. `getOrchardMigrationSdk()` returns null), so those call sites still have *a* real gate
// instead of a bare `> 0L`/`== MigrationState.Complete` check. Matches the Rust-layer
// `MIGRATION_DUST_THRESHOLD_ZATOSHI` in `migration.rs` as of this writing.
const val MIGRATION_DUST_THRESHOLD_ZATOSHI = 100_000L
