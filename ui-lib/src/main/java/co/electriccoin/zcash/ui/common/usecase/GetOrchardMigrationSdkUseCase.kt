package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider

/**
 * Resolves the real, Rust-backed [OrchardMigrationSdk] for the wallet account the migration flow
 * is actually running against, or `null` if there is no wallet yet.
 *
 * [OrchardMigrationSdk.new] needs the wallet's network and lightwalletd endpoint (only known once
 * a wallet exists — read from [PersistableWalletProvider]) and the specific account (whichever one
 * is currently selected in the app — Zodl/Keystone or Zashi — via [GetSelectedWalletAccountUseCase],
 * never auto-picked by the SDK itself). This use case is the single seam combining all three, so
 * every migration call site injects just this instead of wiring them separately.
 *
 * Returns `null` rather than throwing when there's no persisted wallet — callers on general,
 * wallet-independent code paths (`CheckMigrationRecoveryUseCase` runs on every `MainActivity`
 * launch, including a fresh install before onboarding) must tolerate that; callers inside an
 * already-active migration flow (a wallet is a precondition for those screens existing at all) can
 * treat a `null` here as an unreachable-in-practice error instead.
 *
 * Unlike `WalletCoordinatorFactory`'s own `OrchardMigrationSdk.new(... account = null)` call (which
 * gates sync before any `Synchronizer`/account selection exists), this always resolves a real
 * selected account whenever a wallet exists.
 */
class GetOrchardMigrationSdkUseCase(
    private val context: Context,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
) {
    suspend operator fun invoke(): OrchardMigrationSdk? {
        val wallet = persistableWalletProvider.getPersistableWallet() ?: return null
        val account = getSelectedWalletAccount()
        return OrchardMigrationSdk.new(
            appContext = context,
            zcashNetwork = wallet.network,
            lightWalletEndpoint = wallet.endpoint,
            account = account.sdkAccount.accountUuid,
        )
    }
}
