package co.electriccoin.zcash.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import androidx.navigation.toRoute
import co.electriccoin.zcash.ui.common.migration.MigrationNavContributor
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.screen.about.AboutArgs
import co.electriccoin.zcash.ui.screen.about.AboutScreen
import co.electriccoin.zcash.ui.screen.accountlist.AccountListArgs
import co.electriccoin.zcash.ui.screen.accountlist.AccountListScreen
import co.electriccoin.zcash.ui.screen.addressbook.AddressBookArgs
import co.electriccoin.zcash.ui.screen.addressbook.AddressBookScreen
import co.electriccoin.zcash.ui.screen.addressbook.SelectABRecipientArgs
import co.electriccoin.zcash.ui.screen.addressbook.SelectABRecipientScreen
import co.electriccoin.zcash.ui.screen.advancedsettings.AdvancedSettingsArgs
import co.electriccoin.zcash.ui.screen.advancedsettings.AdvancedSettingsScreen
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugArgs
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.DebugScreen
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.db.DebugDBArgs
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.db.DebugDBScreen
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.orchardbalance.DebugOrchardBalanceArgs
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.orchardbalance.DebugOrchardBalanceScreen
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.text.DebugTextArgs
import co.electriccoin.zcash.ui.screen.advancedsettings.debug.text.DebugTextScreen
import co.electriccoin.zcash.ui.screen.balances.breakdown.BalanceBreakdownArgs
import co.electriccoin.zcash.ui.screen.balances.breakdown.BalanceBreakdownScreen
import co.electriccoin.zcash.ui.screen.balances.spendable.SpendableBalanceArgs
import co.electriccoin.zcash.ui.screen.balances.spendable.SpendableBalanceScreen
import co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerArgs
import co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerScreen
import co.electriccoin.zcash.ui.screen.connectkeystone.ConnectKeystoneArgs
import co.electriccoin.zcash.ui.screen.connectkeystone.ConnectKeystoneScreen
import co.electriccoin.zcash.ui.screen.connectkeystone.connected.KeystoneConnectedArgs
import co.electriccoin.zcash.ui.screen.connectkeystone.connected.KeystoneConnectedScreen
import co.electriccoin.zcash.ui.screen.connectkeystone.date.KeystoneDateArgs
import co.electriccoin.zcash.ui.screen.connectkeystone.date.KeystoneFirstTransactionScreen
import co.electriccoin.zcash.ui.screen.connectkeystone.estimation.KeystoneEstimationArgs
import co.electriccoin.zcash.ui.screen.connectkeystone.estimation.KeystoneFirstTransactionEstimationScreen
import co.electriccoin.zcash.ui.screen.connectkeystone.explainer.KeystoneExplainerScreen
import co.electriccoin.zcash.ui.screen.connectkeystone.explainer.KeystoneExplainerScreenArgs
import co.electriccoin.zcash.ui.screen.connectkeystone.height.KeystoneHeightArgs
import co.electriccoin.zcash.ui.screen.connectkeystone.height.KeystoneWBHScreen
import co.electriccoin.zcash.ui.screen.connectkeystone.neworactive.KeystoneNewOrActiveArgs
import co.electriccoin.zcash.ui.screen.connectkeystone.neworactive.KeystoneNewOrActiveScreen
import co.electriccoin.zcash.ui.screen.contact.AddGenericABContactArgs
import co.electriccoin.zcash.ui.screen.contact.AddGenericABContactScreen
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactScreen
import co.electriccoin.zcash.ui.screen.contact.UpdateGenericABContactArgs
import co.electriccoin.zcash.ui.screen.contact.UpdateGenericABContactScreen
import co.electriccoin.zcash.ui.screen.crashreporting.AndroidCrashReportingOptIn
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiArgs
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiScreen
import co.electriccoin.zcash.ui.screen.disconnect.DisconnectArgs
import co.electriccoin.zcash.ui.screen.disconnect.DisconnectScreen
import co.electriccoin.zcash.ui.screen.error.AndroidErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.AndroidErrorDialog
import co.electriccoin.zcash.ui.screen.error.ErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.ErrorDialog
import co.electriccoin.zcash.ui.screen.error.SyncErrorArgs
import co.electriccoin.zcash.ui.screen.error.SyncErrorScreen
import co.electriccoin.zcash.ui.screen.exchangerate.optin.ExchangeRateOptInArgs
import co.electriccoin.zcash.ui.screen.exchangerate.optin.ExchangeRateOptInScreen
import co.electriccoin.zcash.ui.screen.exchangerate.picker.CurrencyConversionPickerArgs
import co.electriccoin.zcash.ui.screen.exchangerate.picker.CurrencyConversionPickerScreen
import co.electriccoin.zcash.ui.screen.exchangerate.settings.ExchangeRateSettingsArgs
import co.electriccoin.zcash.ui.screen.exchangerate.settings.ExchangeRateSettingsScreen
import co.electriccoin.zcash.ui.screen.exportdata.WrapExportPrivateData
import co.electriccoin.zcash.ui.screen.feedback.FeedbackArgs
import co.electriccoin.zcash.ui.screen.feedback.FeedbackScreen
import co.electriccoin.zcash.ui.screen.gift.GiftCardArgs
import co.electriccoin.zcash.ui.screen.gift.GiftCardListArgs
import co.electriccoin.zcash.ui.screen.gift.GiftCardListScreen
import co.electriccoin.zcash.ui.screen.gift.GiftCardScreen
import co.electriccoin.zcash.ui.screen.gift.GiftClaimArgs
import co.electriccoin.zcash.ui.screen.gift.GiftClaimScreen
import co.electriccoin.zcash.ui.screen.heightinfo.HeightInfoArgs
import co.electriccoin.zcash.ui.screen.heightinfo.HeightInfoScreen
import co.electriccoin.zcash.ui.screen.home.AndroidHome
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.home.backup.AndroidWalletBackupDetail
import co.electriccoin.zcash.ui.screen.home.backup.AndroidWalletBackupInfo
import co.electriccoin.zcash.ui.screen.home.backup.SeedBackupInfo
import co.electriccoin.zcash.ui.screen.home.backup.WalletBackupDetail
import co.electriccoin.zcash.ui.screen.home.disconnected.AndroidWalletDisconnectedInfo
import co.electriccoin.zcash.ui.screen.home.disconnected.WalletDisconnectedInfo
import co.electriccoin.zcash.ui.screen.home.reporting.AndroidCrashReportOptIn
import co.electriccoin.zcash.ui.screen.home.reporting.CrashReportOptIn
import co.electriccoin.zcash.ui.screen.home.restoring.AndroidWalletRestoringInfo
import co.electriccoin.zcash.ui.screen.home.restoring.WalletRestoringInfo
import co.electriccoin.zcash.ui.screen.home.resyncing.AndroidWalletResyncingInfo
import co.electriccoin.zcash.ui.screen.home.resyncing.WalletResyncingInfo
import co.electriccoin.zcash.ui.screen.home.shieldfunds.AndroidShieldFundsInfo
import co.electriccoin.zcash.ui.screen.home.shieldfunds.ShieldFundsInfo
import co.electriccoin.zcash.ui.screen.home.syncing.AndroidWalletSyncingInfo
import co.electriccoin.zcash.ui.screen.home.syncing.WalletSyncingInfo
import co.electriccoin.zcash.ui.screen.home.updating.AndroidWalletUpdatingInfo
import co.electriccoin.zcash.ui.screen.home.updating.WalletUpdatingInfo
import co.electriccoin.zcash.ui.screen.hotfix.enhancement.EnhancementHotfixArgs
import co.electriccoin.zcash.ui.screen.hotfix.enhancement.EnhancementHotfixScreen
import co.electriccoin.zcash.ui.screen.hotfix.ephemeral.EphemeralHotfixArgs
import co.electriccoin.zcash.ui.screen.hotfix.ephemeral.EphemeralHotfixScreen
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsScreen
import co.electriccoin.zcash.ui.screen.integrations.IntegrationsArgs
import co.electriccoin.zcash.ui.screen.integrations.IntegrationsScreen
import co.electriccoin.zcash.ui.screen.ironwood.IronwoodAnnouncementArgs
import co.electriccoin.zcash.ui.screen.ironwood.IronwoodAnnouncementScreen
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenScreen
import co.electriccoin.zcash.ui.screen.more.MoreArgs
import co.electriccoin.zcash.ui.screen.more.MoreScreen
import co.electriccoin.zcash.ui.screen.onramp.OnrampArgs
import co.electriccoin.zcash.ui.screen.onramp.OnrampScreen
import co.electriccoin.zcash.ui.screen.qrcode.QrCodeScreen
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import co.electriccoin.zcash.ui.screen.receive.ReceiveArgs
import co.electriccoin.zcash.ui.screen.receive.ReceiveScreen
import co.electriccoin.zcash.ui.screen.receive.info.ShieldedAddressInfoArgs
import co.electriccoin.zcash.ui.screen.receive.info.ShieldedAddressInfoScreen
import co.electriccoin.zcash.ui.screen.receive.info.TransparentAddressInfoArgs
import co.electriccoin.zcash.ui.screen.receive.info.TransparentAddressInfoScreen
import co.electriccoin.zcash.ui.screen.request.RequestScreen
import co.electriccoin.zcash.ui.screen.restore.date.RestoreBDDateArgs
import co.electriccoin.zcash.ui.screen.restore.date.RestoreBDDateScreen
import co.electriccoin.zcash.ui.screen.restore.estimation.RestoreBDEstimationArgs
import co.electriccoin.zcash.ui.screen.restore.estimation.RestoreBDEstimationScreen
import co.electriccoin.zcash.ui.screen.restore.height.AndroidRestoreBDHeight
import co.electriccoin.zcash.ui.screen.restore.height.RestoreBDHeight
import co.electriccoin.zcash.ui.screen.restore.info.AndroidSeedInfo
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedArgs
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedScreen
import co.electriccoin.zcash.ui.screen.resync.confirm.ResyncConfirmArgs
import co.electriccoin.zcash.ui.screen.resync.confirm.ResyncConfirmScreen
import co.electriccoin.zcash.ui.screen.resync.date.ResyncDateArgs
import co.electriccoin.zcash.ui.screen.resync.date.ResyncDateScreen
import co.electriccoin.zcash.ui.screen.resync.estimation.ResyncEstimationArgs
import co.electriccoin.zcash.ui.screen.resync.estimation.ResyncEstimationScreen
import co.electriccoin.zcash.ui.screen.resync.height.ResyncHeightArgs
import co.electriccoin.zcash.ui.screen.resync.height.ResyncHeightScreen
import co.electriccoin.zcash.ui.screen.reviewtransaction.AndroidReviewTransaction
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressArgs
import co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressScreen
import co.electriccoin.zcash.ui.screen.scan.ScanZashiAddressScreen
import co.electriccoin.zcash.ui.screen.scan.thirdparty.AndroidThirdPartyScan
import co.electriccoin.zcash.ui.screen.scan.thirdparty.ThirdPartyScan
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystonePCZTRequest
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystoneSignInRequest
import co.electriccoin.zcash.ui.screen.scankeystone.WrapScanKeystonePCZTRequest
import co.electriccoin.zcash.ui.screen.scankeystone.WrapScanKeystoneSignInRequest
import co.electriccoin.zcash.ui.screen.securitysettings.SecuritySettingsArgs
import co.electriccoin.zcash.ui.screen.securitysettings.SecuritySettingsScreen
import co.electriccoin.zcash.ui.screen.selectkeystoneaccount.AndroidSelectKeystoneAccount
import co.electriccoin.zcash.ui.screen.selectkeystoneaccount.SelectKeystoneAccount
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pPaymentMethodArgs
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pPaymentMethodScreen
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pTransactionsArgs
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pTransactionsScreen
import co.electriccoin.zcash.ui.screen.settings.portfoliochart.PortfolioChartSettingsArgs
import co.electriccoin.zcash.ui.screen.settings.portfoliochart.PortfolioChartSettingsScreen
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionScreen
import co.electriccoin.zcash.ui.screen.swap.SwapArgs
import co.electriccoin.zcash.ui.screen.swap.SwapScreen
import co.electriccoin.zcash.ui.screen.swap.SwapTab
import co.electriccoin.zcash.ui.screen.swap.UpiOfframpArgs
import co.electriccoin.zcash.ui.screen.swap.ab.AddSwapABContactArgs
import co.electriccoin.zcash.ui.screen.swap.ab.AddSwapABContactScreen
import co.electriccoin.zcash.ui.screen.swap.ab.SelectABSwapRecipientArgs
import co.electriccoin.zcash.ui.screen.swap.ab.SelectSwapABRecipientScreen
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailArgs
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailScreen
import co.electriccoin.zcash.ui.screen.swap.detail.support.SwapSupportArgs
import co.electriccoin.zcash.ui.screen.swap.detail.support.SwapSupportScreen
import co.electriccoin.zcash.ui.screen.swap.info.DepositSwapInfoArgs
import co.electriccoin.zcash.ui.screen.swap.info.DepositSwapInfoScreen
import co.electriccoin.zcash.ui.screen.swap.info.SwapInfoArgs
import co.electriccoin.zcash.ui.screen.swap.info.SwapInfoScreen
import co.electriccoin.zcash.ui.screen.swap.info.SwapRefundAddressInfoArgs
import co.electriccoin.zcash.ui.screen.swap.info.SwapRefundAddressInfoScreen
import co.electriccoin.zcash.ui.screen.swap.lock.EphemeralLockArgs
import co.electriccoin.zcash.ui.screen.swap.lock.EphemeralLockScreen
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationArgs
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationScreen
import co.electriccoin.zcash.ui.screen.swap.peer.PeerCashOutArgs
import co.electriccoin.zcash.ui.screen.swap.peer.PeerCashOutScreen
import co.electriccoin.zcash.ui.screen.swap.peer.order.PeerOrderArgs
import co.electriccoin.zcash.ui.screen.swap.peer.order.PeerOrderScreen
import co.electriccoin.zcash.ui.screen.swap.peer.progress.PeerCashOutProgressArgs
import co.electriccoin.zcash.ui.screen.swap.peer.progress.PeerCashOutProgressScreen
import co.electriccoin.zcash.ui.screen.swap.picker.SwapAssetPickerArgs
import co.electriccoin.zcash.ui.screen.swap.picker.SwapAssetPickerScreen
import co.electriccoin.zcash.ui.screen.swap.picker.SwapBlockchainPickerArgs
import co.electriccoin.zcash.ui.screen.swap.picker.SwapBlockchainPickerScreen
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteArgs
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteScreen
import co.electriccoin.zcash.ui.screen.swap.slippage.SwapSlippageArgs
import co.electriccoin.zcash.ui.screen.swap.slippage.SwapSlippageScreen
import co.electriccoin.zcash.ui.screen.swap.upi.bridge.BridgeToBaseArgs
import co.electriccoin.zcash.ui.screen.swap.upi.bridge.BridgeToBaseScreen
import co.electriccoin.zcash.ui.screen.swap.upi.progress.UpiOfframpProgressArgs
import co.electriccoin.zcash.ui.screen.swap.upi.progress.UpiOfframpProgressScreen
import co.electriccoin.zcash.ui.screen.swap.upi.scan.ScanUpiArgs
import co.electriccoin.zcash.ui.screen.swap.upi.scan.ScanUpiScreen
import co.electriccoin.zcash.ui.screen.swap.upi.toPrescannedMerchantQr
import co.electriccoin.zcash.ui.screen.tabs.AndroidTabs
import co.electriccoin.zcash.ui.screen.tabs.TabsArgs
import co.electriccoin.zcash.ui.screen.taxexport.AndroidTaxExport
import co.electriccoin.zcash.ui.screen.taxexport.TaxExport
import co.electriccoin.zcash.ui.screen.texunsupported.AndroidTEXUnsupported
import co.electriccoin.zcash.ui.screen.texunsupported.TEXUnsupportedArgs
import co.electriccoin.zcash.ui.screen.topup.TopUpArgs
import co.electriccoin.zcash.ui.screen.topup.TopUpScreen
import co.electriccoin.zcash.ui.screen.tor.optin.TorOptInArgs
import co.electriccoin.zcash.ui.screen.tor.optin.TorOptInScreen
import co.electriccoin.zcash.ui.screen.tor.settings.TorSettingsArgs
import co.electriccoin.zcash.ui.screen.tor.settings.TorSettingsScreen
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailScreen
import co.electriccoin.zcash.ui.screen.transactionfilters.TransactionFiltersArgs
import co.electriccoin.zcash.ui.screen.transactionfilters.TransactionFiltersScreen
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityHistoryArgs
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityHistoryScreen
import co.electriccoin.zcash.ui.screen.transactionnote.AndroidTransactionNote
import co.electriccoin.zcash.ui.screen.transactionnote.TransactionNote
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressScreen
import co.electriccoin.zcash.ui.screen.unifiedsend.UnifiedSendArgs
import co.electriccoin.zcash.ui.screen.unifiedsend.UnifiedSendScreen
import co.electriccoin.zcash.ui.screen.viewingkeyexport.ViewingKeyExportArgs
import co.electriccoin.zcash.ui.screen.viewingkeyexport.ViewingKeyExportScreen
import co.electriccoin.zcash.ui.screen.walletbackup.AndroidWalletBackup
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackup
import co.electriccoin.zcash.ui.screen.warning.WrapNotEnoughSpace
import co.electriccoin.zcash.ui.screen.warning.viewmodel.StorageCheckViewModel
import co.electriccoin.zcash.ui.screen.welcome.WelcomeGateVM
import co.electriccoin.zcash.ui.screen.whatsnew.WrapWhatsNew
import org.koin.androidx.compose.koinViewModel
import xyz.justzappit.offramp.p2p.CurrencyCode

fun NavGraphBuilder.walletNavGraph(
    storageCheckViewModel: StorageCheckViewModel,
    walletViewModel: WalletViewModel,
    navigationRouter: NavigationRouter,
) {
    navigation<MainAppGraph>(startDestination = TabsArgs) {
        // Zapp-style bottom-tab shell — Pay, Chats, Contacts, Settings.
        composable<TabsArgs> {
            AndroidTabs()

            val showIronwoodAnnouncement by
                walletViewModel.shouldShowIronwoodAnnouncement.collectAsStateWithLifecycle()
            // Upstream fires this from its home destination, which can only ever be home. Here
            // TabsArgs also hosts the welcome gate and the onboarding/restore flows, so hold the
            // announcement until both durable gates report home is the thing on screen —
            // otherwise a funded wallet that re-enters onboarding spends its one-time
            // announcement on a user who is still setting up.
            val welcomeGateVM = koinViewModel<WelcomeGateVM>()
            val isWelcomeDismissed by welcomeGateVM.isWelcomeDismissed.collectAsStateWithLifecycle()
            val isOnboardingCompleted by welcomeGateVM.isOnboardingCompleted.collectAsStateWithLifecycle()
            val isHomeVisible = isWelcomeDismissed == true && isOnboardingCompleted == true
            LaunchedEffect(showIronwoodAnnouncement, isHomeVisible) {
                if (showIronwoodAnnouncement && isHomeVisible) {
                    navigationRouter.forward(IronwoodAnnouncementArgs)
                }
            }

            val isEnoughSpace by storageCheckViewModel.isEnoughSpace.collectAsStateWithLifecycle()
            if (isEnoughSpace == false) {
                navigationRouter.forward(NavigationTargets.NOT_ENOUGH_SPACE)
            }
        }
        // Legacy Zashi home — hidden from the default flow but still registered
        // so send/receive/swap paths that navigate "back to home" keep working.
        composable<HomeArgs> { AndroidHome() }
        composable<IronwoodAnnouncementArgs> { IronwoodAnnouncementScreen() }
        composable<MoreArgs> { MoreScreen() }
        composable<AdvancedSettingsArgs> { AdvancedSettingsScreen() }
        composable<ViewingKeyExportArgs> { ViewingKeyExportScreen() }
        composable<GiftCardArgs> { GiftCardScreen() }
        composable<GiftCardListArgs> { GiftCardListScreen() }
        composable<GiftClaimArgs> { GiftClaimScreen(it.toRoute()) }
        composable<ChooseServerArgs> { ChooseServerScreen() }
        composable<P2pTransactionsArgs> { P2pTransactionsScreen() }
        composable<P2pPaymentMethodArgs> { P2pPaymentMethodScreen() }
        composable<PortfolioChartSettingsArgs> { PortfolioChartSettingsScreen() }
        composable<SecuritySettingsArgs> { SecuritySettingsScreen() }
        composable<WalletBackup> { AndroidWalletBackup(it.toRoute()) }
        composable<FeedbackArgs> { FeedbackScreen() }
        composable<ResetZashiArgs> { ResetZashiScreen() }
        composable<AboutArgs> { AboutScreen() }
        composable(NavigationTargets.WHATS_NEW) { WrapWhatsNew() }
        dialogComposable<IntegrationsArgs> { IntegrationsScreen() }
        composable<ExchangeRateSettingsArgs> { ExchangeRateSettingsScreen() }
        composable(NavigationTargets.CRASH_REPORTING_OPT_IN) { AndroidCrashReportingOptIn() }
        composable<ScanKeystoneSignInRequest> { WrapScanKeystoneSignInRequest() }
        composable<ScanKeystonePCZTRequest> { WrapScanKeystonePCZTRequest() }
        composable<SignKeystoneTransactionArgs> { SignKeystoneTransactionScreen() }
        dialogComposable<AccountListArgs> { AccountListScreen() }
        composable<ScanArgs> { ScanZashiAddressScreen(it.toRoute()) }
        composable(NavigationTargets.EXPORT_PRIVATE_DATA) { WrapExportPrivateData() }
        composable(NavigationTargets.NOT_ENOUGH_SPACE) {
            WrapNotEnoughSpace(
                goPrevious = { navigationRouter.back() },
                goSettings = { navigationRouter.forward(MoreArgs) }
            )
        }
        composable<AddressBookArgs> { AddressBookScreen() }
        composable<SelectABRecipientArgs> { SelectABRecipientScreen() }
        composable<AddZashiABContactArgs> { AddZashiABContactScreen(it.toRoute()) }
        composable(
            route = "${NavigationTargets.QR_CODE}/{${NavigationArgs.ADDRESS_TYPE}}",
            arguments = listOf(navArgument(NavigationArgs.ADDRESS_TYPE) { type = NavType.Companion.IntType })
        ) { backStackEntry ->
            val addressType =
                backStackEntry.arguments?.getInt(NavigationArgs.ADDRESS_TYPE) ?: ReceiveAddressType.Unified.ordinal
            QrCodeScreen(addressType)
        }
        composable(
            route = "${NavigationTargets.REQUEST}/{${NavigationArgs.ADDRESS_TYPE}}",
            arguments = listOf(navArgument(NavigationArgs.ADDRESS_TYPE) { type = NavType.Companion.IntType })
        ) { backStackEntry ->
            val addressType =
                backStackEntry.arguments?.getInt(NavigationArgs.ADDRESS_TYPE) ?: ReceiveAddressType.Unified.ordinal
            RequestScreen(addressType)
        }
        composable<ConnectKeystoneArgs> { ConnectKeystoneScreen() }
        composable<KeystoneConnectedArgs> { KeystoneConnectedScreen() }
        dialogComposable<KeystoneExplainerScreenArgs> { KeystoneExplainerScreen() }
        composable<KeystoneNewOrActiveArgs> { KeystoneNewOrActiveScreen(it.toRoute()) }
        composable<KeystoneDateArgs> { KeystoneFirstTransactionScreen(it.toRoute()) }
        composable<KeystoneEstimationArgs> { KeystoneFirstTransactionEstimationScreen(it.toRoute()) }
        composable<KeystoneHeightArgs> { KeystoneWBHScreen(it.toRoute()) }
        dialogComposable<HeightInfoArgs> { HeightInfoScreen() }
        composable<KeepOpenArgs> { KeepOpenScreen(it.toRoute()) }
        composable<SelectKeystoneAccount> { AndroidSelectKeystoneAccount(it.toRoute()) }
        composable<ReviewTransactionArgs> { AndroidReviewTransaction() }
        composable<TransactionProgressArgs> { TransactionProgressScreen(it.toRoute()) }
        composable<ActivityHistoryArgs> { ActivityHistoryScreen() }
        dialogComposable<TransactionFiltersArgs> { TransactionFiltersScreen() }
        composable<TransactionDetailArgs> { TransactionDetailScreen(it.toRoute()) }
        dialogComposable<TransactionNote> { AndroidTransactionNote(it.toRoute()) }
        composable<TaxExport> { AndroidTaxExport() }
        composable<ReceiveArgs> { ReceiveScreen() }
        dialogComposable<TEXUnsupportedArgs> { AndroidTEXUnsupported() }
        dialogComposable<InsufficientFundsArgs> { InsufficientFundsScreen() }
        dialogComposable<TopUpArgs> { TopUpScreen() }
        dialogComposable<SeedInfo> { AndroidSeedInfo() }
        composable<WalletBackupDetail> { AndroidWalletBackupDetail(it.toRoute()) }
        dialogComposable<SeedBackupInfo> { AndroidWalletBackupInfo() }
        dialogComposable<ShieldFundsInfo> { AndroidShieldFundsInfo() }
        dialogComposable<WalletDisconnectedInfo> { AndroidWalletDisconnectedInfo() }
        dialogComposable<WalletRestoringInfo> { AndroidWalletRestoringInfo() }
        dialogComposable<WalletSyncingInfo> { AndroidWalletSyncingInfo() }
        dialogComposable<WalletResyncingInfo> { AndroidWalletResyncingInfo() }
        dialogComposable<WalletUpdatingInfo> { AndroidWalletUpdatingInfo() }
        dialogComposable<ErrorDialog> { AndroidErrorDialog() }
        dialogComposable<ErrorBottomSheet> { AndroidErrorBottomSheet() }
        dialogComposable<SyncErrorArgs> { SyncErrorScreen() }
        dialogComposable<SpendableBalanceArgs> { SpendableBalanceScreen() }
        dialogComposable<BalanceBreakdownArgs> { BalanceBreakdownScreen() }
        composable<CrashReportOptIn> { AndroidCrashReportOptIn() }
        composable<ThirdPartyScan> { AndroidThirdPartyScan() }
        dialogComposable<SwapAssetPickerArgs> { SwapAssetPickerScreen(it.toRoute()) }
        dialogComposable<SwapBlockchainPickerArgs> { SwapBlockchainPickerScreen(it.toRoute()) }
        composable<CurrencyConversionPickerArgs> { CurrencyConversionPickerScreen(it.toRoute()) }
        composable<SwapArgs> { SwapScreen() }
        composable<OnrampArgs> { OnrampScreen(it.toRoute()) }
        composable<UpiOfframpArgs> {
            val offrampArgs = it.toRoute<UpiOfframpArgs>()
            SwapScreen(
                tab = SwapTab.OFFRAMP,
                offrampCurrency = CurrencyCode.fromCodeOrNull(offrampArgs.currencyCode) ?: CurrencyCode.Inr,
                prescannedMerchantQr = offrampArgs.toPrescannedMerchantQr(),
            )
        }
        composable<UpiOfframpProgressArgs> { UpiOfframpProgressScreen(it.toRoute()) }
        composable<PeerCashOutArgs> { PeerCashOutScreen(it.toRoute()) }
        composable<PeerCashOutProgressArgs> { PeerCashOutProgressScreen(it.toRoute()) }
        composable<PeerOrderArgs> { PeerOrderScreen(it.toRoute()) }
        composable<BridgeToBaseArgs> { BridgeToBaseScreen(it.toRoute()) }
        composable<ScanUpiArgs> { ScanUpiScreen(it.toRoute()) }
        dialogComposable<SwapSlippageArgs> { SwapSlippageScreen(it.toRoute()) }
        dialogComposable<SwapInfoArgs> { SwapInfoScreen() }
        dialogComposable<DepositSwapInfoArgs> { DepositSwapInfoScreen() }
        dialogComposable<SwapQuoteArgs> { SwapQuoteScreen() }
        composable<ScanGenericAddressArgs> { ScanGenericAddressScreen(it.toRoute()) }
        composable<SelectABSwapRecipientArgs> { SelectSwapABRecipientScreen(it.toRoute()) }
        composable<AddSwapABContactArgs> { AddSwapABContactScreen(it.toRoute()) }
        composable<AddGenericABContactArgs> { AddGenericABContactScreen(it.toRoute()) }
        composable<UpdateGenericABContactArgs> { UpdateGenericABContactScreen(it.toRoute()) }
        composable<TorSettingsArgs> { TorSettingsScreen() }
        composable<TorOptInArgs> { TorOptInScreen() }
        dialogComposable<ShieldedAddressInfoArgs> { ShieldedAddressInfoScreen() }
        dialogComposable<TransparentAddressInfoArgs> { TransparentAddressInfoScreen() }
        composable<ExchangeRateOptInArgs> { ExchangeRateOptInScreen() }
        composable<UnifiedSendArgs> { UnifiedSendScreen(it.toRoute()) }
        composable<ORSwapConfirmationArgs> { ORSwapConfirmationScreen() }
        composable<SwapDetailArgs> { SwapDetailScreen(it.toRoute()) }
        dialogComposable<SwapRefundAddressInfoArgs> { SwapRefundAddressInfoScreen() }
        dialogComposable<SwapSupportArgs> { SwapSupportScreen(it.toRoute()) }
        dialogComposable<EphemeralHotfixArgs> { EphemeralHotfixScreen(it.toRoute()) }
        dialogComposable<EnhancementHotfixArgs> { EnhancementHotfixScreen() }
        dialogComposable<EphemeralLockArgs> { EphemeralLockScreen() }
        composable<DebugArgs> { DebugScreen() }
        composable<DebugDBArgs> { DebugDBScreen() }
        composable<DebugOrchardBalanceArgs> { DebugOrchardBalanceScreen() }
        dialogComposable<DebugTextArgs> { DebugTextScreen(it.toRoute()) }
        composable<ResyncConfirmArgs> { ResyncConfirmScreen() }
        composable<ResyncDateArgs> { ResyncDateScreen(it.toRoute()) }
        composable<ResyncEstimationArgs> { ResyncEstimationScreen(it.toRoute()) }
        composable<ResyncHeightArgs> { ResyncHeightScreen() }
        composable<DisconnectArgs> { DisconnectScreen() }
        composable<RestoreSeedArgs> { RestoreSeedScreen() }
        composable<RestoreBDHeight> { AndroidRestoreBDHeight(it.toRoute()) }
        composable<RestoreBDDateArgs> { RestoreBDDateScreen(it.toRoute()) }
        composable<RestoreBDEstimationArgs> { RestoreBDEstimationScreen(it.toRoute()) }

        // P2P Chat sub-graph — see ChatNavGraph.kt
        chatNavGraph(navigationRouter)

        // Migration destinations are contributed by the feature-migration module — see
        // MigrationNavContributor in MigrationContracts.kt (wired via Koin in the app module).
        org.koin.mp.KoinPlatform.getKoin().getAll<MigrationNavContributor>().forEach {
            it.contribute(this)
        }
    }
}
