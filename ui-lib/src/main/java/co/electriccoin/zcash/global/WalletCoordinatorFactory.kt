package co.electriccoin.zcash.global

import android.content.Context
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

internal operator fun WalletCoordinator.Companion.invoke(
    context: Context,
    persistableWalletProvider: PersistableWalletProvider,
    isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    isExchangeRateEnabledStorageProvider: IsExchangeRateEnabledStorageProvider,
): WalletCoordinator =
    WalletCoordinator(
        context = context,
        persistableWallet = persistableWalletProvider.persistableWallet,
        accountName = context.getString(R.string.accounts_zashi),
        keySource = "zashi",
        isTorEnabled = isTorEnabledStorageProvider.observe(),
        isExchangeRateEnabled = isExchangeRateEnabledStorageProvider.observe(),
        isSyncBlocked = isSyncBlocked(context, persistableWalletProvider),
        // `isSlipstreamEnabled` is gone from WalletCoordinator in SDK 3.1.0, but the engine is
        // not: it moved out of the MIT-licensed `zcash-android-sdk` into its own AGPL-licensed
        // `zcash-android-sdk-slipstream` artifact, which still arrives transitively via
        // sdk-incubator and is what the wallet actually runs (verified on-device: the running
        // build stacks through com.zodl.slipstream.SlipstreamSynchronizer.prepare). The parameter
        // disappeared because the engine stopped being opt-in per wallet, not because sync fell
        // back to CompactBlockProcessor. Nothing here needs to replace it. See docs/voting.md.
    )

/**
 * [OrchardMigrationSdk.new] needs the wallet's network and endpoint, which only exist once a
 * wallet has been created — reconstructed on every [PersistableWalletProvider.persistableWallet]
 * emission (the same reactive shape [WalletCoordinator] itself uses internally to rebuild its
 * `Synchronizer`), so a fresh install with no wallet yet just reports "not blocked" instead of
 * needing an account/network that don't exist. No specific account is selected yet at this point
 * either (this runs before any [cash.z.ecc.android.sdk.Synchronizer] — and so before the account
 * picker itself — exists), so `account = null`: [OrchardMigrationSdk.isSyncBlocked] then checks
 * every account in the wallet rather than assuming one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun isSyncBlocked(context: Context, persistableWalletProvider: PersistableWalletProvider) =
    persistableWalletProvider.persistableWallet.flatMapLatest { wallet ->
        if (wallet == null) {
            flowOf(false)
        } else {
            OrchardMigrationSdk
                .new(
                    appContext = context,
                    zcashNetwork = wallet.network,
                    lightWalletEndpoint = wallet.endpoint,
                    account = null,
                ).isSyncBlocked()
        }
    }
