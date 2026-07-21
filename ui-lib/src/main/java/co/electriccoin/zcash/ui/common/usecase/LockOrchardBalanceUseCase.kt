package co.electriccoin.zcash.ui.common.usecase

/**
 * Marks the dust balance left behind in Orchard after migration as unspendable. Thin wrapper
 * around [OrchardMigrationSdk.lockRemainingOrchardBalance] — see its kdoc for the stub caveat
 * (no real Rust-side enforcement yet).
 */
class LockOrchardBalanceUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
) {
    suspend operator fun invoke() {
        getOrchardMigrationSdk()?.lockRemainingOrchardBalance()
    }
}
