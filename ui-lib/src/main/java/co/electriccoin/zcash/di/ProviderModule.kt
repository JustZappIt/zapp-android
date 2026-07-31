package co.electriccoin.zcash.di

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProviderImpl
import co.electriccoin.zcash.ui.common.provider.BlockchainProvider
import co.electriccoin.zcash.ui.common.provider.BlockchainProviderImpl
import co.electriccoin.zcash.ui.common.provider.CMCApiProvider
import co.electriccoin.zcash.ui.common.provider.CMCApiProviderImpl
import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProvider
import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.ChatNotifier
import co.electriccoin.zcash.ui.common.provider.ChatNotifierImpl
import co.electriccoin.zcash.ui.common.provider.ChatSendContextProvider
import co.electriccoin.zcash.ui.common.provider.CrashReportingStorageProvider
import co.electriccoin.zcash.ui.common.provider.CrashReportingStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.EphemeralAddressStorageProvider
import co.electriccoin.zcash.ui.common.provider.EphemeralAddressStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.provider.GetZcashCurrencyProvider
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.common.provider.HttpClientProviderImpl
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.IsIronwoodAnnouncementShownStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsIronwoodAnnouncementShownStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.IsKeepScreenOnDuringRestoreProvider
import co.electriccoin.zcash.ui.common.provider.IsKeepScreenOnDuringRestoreProviderImpl
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKProvider
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKProviderImpl
import co.electriccoin.zcash.ui.common.provider.KtorNearApiProvider
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.NearApiProvider
import co.electriccoin.zcash.ui.common.provider.NearBridgeOfframpFunding
import co.electriccoin.zcash.ui.common.provider.NearPullbackOfframpRefund
import co.electriccoin.zcash.ui.common.provider.OfframpBridgeWallet
import co.electriccoin.zcash.ui.common.provider.OfframpCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.provider.OfframpCheckpointStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.OfframpTopUpCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.provider.OfframpTopUpCheckpointStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.OfframpTopUpPreview
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProviderImpl
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProviderImpl
import co.electriccoin.zcash.ui.common.provider.PreferredP2pPaymentMethodProvider
import co.electriccoin.zcash.ui.common.provider.PreferredP2pPaymentMethodProviderImpl
import co.electriccoin.zcash.ui.common.provider.RealOfframpBridgeWallet
import co.electriccoin.zcash.ui.common.provider.RestoreTimestampStorageProvider
import co.electriccoin.zcash.ui.common.provider.RestoreTimestampStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.SelectedAccountUUIDProvider
import co.electriccoin.zcash.ui.common.provider.SelectedAccountUUIDProviderImpl
import co.electriccoin.zcash.ui.common.provider.ShieldFundsInfoProvider
import co.electriccoin.zcash.ui.common.provider.ShieldFundsInfoProviderImpl
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProvider
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProviderImpl
import co.electriccoin.zcash.ui.common.provider.SwapAssetProvider
import co.electriccoin.zcash.ui.common.provider.SwapAssetProviderImpl
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProviderImpl
import co.electriccoin.zcash.ui.common.provider.TokenIconProvider
import co.electriccoin.zcash.ui.common.provider.TokenIconProviderImpl
import co.electriccoin.zcash.ui.common.provider.TokenNameProvider
import co.electriccoin.zcash.ui.common.provider.TokenNameProviderImpl
import co.electriccoin.zcash.ui.common.provider.WalletBackupConsentStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupConsentStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.WalletBackupRemindMeCountStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupRemindMeCountStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.WalletBackupRemindMeTimestampStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupRemindMeTimestampStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProviderImpl
import co.electriccoin.zcash.ui.common.provider.WalletSeedPhraseSource
import co.electriccoin.zcash.ui.common.push.ChatNotificationState
import co.electriccoin.zcash.ui.common.push.ChatNotificationTiming
import co.electriccoin.zcash.ui.common.push.ChatPushBackend
import co.electriccoin.zcash.ui.common.push.ChatPushBackendImpl
import co.electriccoin.zcash.ui.common.push.PushRegistrar
import io.ktor.client.HttpClient
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.BundlerClient
import xyz.justzappit.evm.rpc.RpcHttpClient
import xyz.justzappit.offramp.account.CachingOfframpAccountProvider
import xyz.justzappit.offramp.account.DevOfframpAccountProvider
import xyz.justzappit.offramp.account.OfframpAccountProvider
import xyz.justzappit.offramp.account.SeedPhraseSource
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.account.StaticOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pConfigProvider
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.config.P2pNetworks
import xyz.justzappit.offramp.funding.NoRouteOfframpRefund
import xyz.justzappit.offramp.funding.NoRouteOfframpTopUp
import xyz.justzappit.offramp.funding.OfframpFunding
import xyz.justzappit.offramp.funding.OfframpRefund
import xyz.justzappit.offramp.funding.OfframpTopUp
import xyz.justzappit.offramp.funding.PreFundedOfframpFunding
import xyz.justzappit.offramp.p2p.DirectPixResolver
import xyz.justzappit.offramp.p2p.DynamicPixResolver
import xyz.justzappit.offramp.p2p.SubgraphClient
import java.util.Locale

const val OFFRAMP_HTTP_CLIENT_QUALIFIER = "offramp_http"

val providerModule =
    module {
        factoryOf(::LightWalletEndpointProvider)
        singleOf(::GetVersionInfoProvider)
        singleOf(::GetZcashCurrencyProvider)
        singleOf(::SelectedAccountUUIDProviderImpl) bind SelectedAccountUUIDProvider::class
        singleOf(::PersistableWalletProviderImpl) bind PersistableWalletProvider::class
        singleOf(::PreferredFiatProviderImpl) bind PreferredFiatProvider::class
        singleOf(::PreferredP2pPaymentMethodProviderImpl) bind PreferredP2pPaymentMethodProvider::class
        singleOf(::SynchronizerProviderImpl) bind SynchronizerProvider::class
        singleOf(::ApplicationStateProviderImpl) bind ApplicationStateProvider::class
        singleOf(::RestoreTimestampStorageProviderImpl) bind RestoreTimestampStorageProvider::class
        singleOf(::WalletBackupRemindMeCountStorageProviderImpl) bind
            WalletBackupRemindMeCountStorageProvider::class
        singleOf(::WalletBackupRemindMeTimestampStorageProviderImpl) bind
            WalletBackupRemindMeTimestampStorageProvider::class
        singleOf(::WalletBackupFlagStorageProviderImpl) bind WalletBackupFlagStorageProvider::class
        singleOf(::IsIronwoodAnnouncementShownStorageProviderImpl) bind
            IsIronwoodAnnouncementShownStorageProvider::class
        singleOf(::WalletBackupConsentStorageProviderImpl) bind WalletBackupConsentStorageProvider::class
        singleOf(::WalletRestoringStateProviderImpl) bind WalletRestoringStateProvider::class
        singleOf(::CrashReportingStorageProviderImpl) bind CrashReportingStorageProvider::class
        singleOf(::ShieldFundsInfoProviderImpl) bind ShieldFundsInfoProvider::class
        singleOf(::IsExchangeRateEnabledStorageProviderImpl) bind IsExchangeRateEnabledStorageProvider::class
        singleOf(::IsTorEnabledStorageProviderImpl) bind IsTorEnabledStorageProvider::class
        singleOf(::BlockchainProviderImpl) bind BlockchainProvider::class
        singleOf(::TokenIconProviderImpl) bind TokenIconProvider::class
        singleOf(::TokenNameProviderImpl) bind TokenNameProvider::class
        singleOf(::KtorNearApiProvider) bind NearApiProvider::class
        factoryOf(::HttpClientProviderImpl) bind HttpClientProvider::class
        factoryOf(::SimpleSwapAssetProviderImpl) bind SimpleSwapAssetProvider::class
        factoryOf(::SwapAssetProviderImpl) bind SwapAssetProvider::class
        factoryOf(::IsKeepScreenOnDuringRestoreProviderImpl) bind IsKeepScreenOnDuringRestoreProvider::class
        singleOf(::EphemeralAddressStorageProviderImpl) bind EphemeralAddressStorageProvider::class
        singleOf(::CMCApiProviderImpl) bind CMCApiProvider::class
        factoryOf(::KeystoneSDKProviderImpl) bind KeystoneSDKProvider::class
        singleOf(::ChatSendContextProvider)
        singleOf(::ChatBlockedKeysStorageProviderImpl) bind ChatBlockedKeysStorageProvider::class
        singleOf(::ChatNotifierImpl) bind ChatNotifier::class
        singleOf(::ChatNotificationState)
        single { ChatNotificationTiming() }
        singleOf(::ChatPushBackendImpl) bind ChatPushBackend::class
        singleOf(::PushRegistrar)

        // UPI offramp infrastructure (evm-lib + offramp-lib config wiring).
        singleOf(::OfframpCheckpointStorageProviderImpl) bind OfframpCheckpointStorageProvider::class
        singleOf(::OfframpTopUpCheckpointStorageProviderImpl) bind OfframpTopUpCheckpointStorageProvider::class
        single<HttpClient>(named(OFFRAMP_HTTP_CLIENT_QUALIFIER)) {
            // Pipe ktor's Logging plugin output through Twig so subgraph + RPC errors land in
            // logcat under our "Twig" tag with the OfframpHttp prefix. Without this, transport
            // failures (ConnectException, SSL handshake, etc.) emit no logcat trace and the only
            // signal is the orchestrator's Failed status emission — which gets rotated out of
            // the buffer before we can grab it.
            // Bundler/RPC/subgraph URLs embed secrets (Pimlico's apikey query param; key-bearing
            // Alchemy/Graph URLs set in local.properties). ktor's Logging plugin logs the request
            // URL at INFO, so mask secret query params before they reach logcat.
            val secretQueryParam = Regex("(?i)(apikey|api_key|api-key|key|token|access_token)=([^&\\s]+)")
            val twigLogger =
                object : io.ktor.client.plugins.logging.Logger {
                    override fun log(message: String) {
                        Twig.debug { "OfframpHttp " + secretQueryParam.replace(message) { "${it.groupValues[1]}=***" } }
                    }
                }
            RpcHttpClient.create(
                config =
                    RpcHttpClient.Config(
                        logger = twigLogger,
                        logLevel = io.ktor.client.plugins.logging.LogLevel.INFO,
                    ),
            )
        }
        single<P2pConfigProvider> {
            // Recognised values are exactly "", "sepolia", "mainnet"; blank defaults to Sepolia
            // for CI / side-by-side installs. A typo like "mainet" must not silently boot the
            // testnet build into the wrong network — fail closed instead.
            when (val net = BuildConfig.P2P_NETWORK.lowercase(Locale.ROOT)) {
                P2pNetworks.MAINNET_NAME -> {
                    P2pConfigProvider(
                        networkName = P2pNetworks.MAINNET_NAME,
                        rpcUrlOverride = BuildConfig.P2P_RPC_URL_BASE_MAINNET.takeIf { it.isNotBlank() },
                        subgraphUrlOverride = BuildConfig.P2P_SUBGRAPH_URL_MAINNET.takeIf { it.isNotBlank() },
                    )
                }

                P2pNetworks.SEPOLIA_NAME, "" -> {
                    P2pConfigProvider(
                        networkName = P2pNetworks.SEPOLIA_NAME,
                        rpcUrlOverride =
                            BuildConfig.P2P_RPC_URL_BASE_SEPOLIA.takeIf { it.isNotBlank() }
                                ?: P2pNetworks.SEPOLIA.rpcUrl,
                        subgraphUrlOverride =
                            BuildConfig.P2P_SUBGRAPH_URL_SEPOLIA.takeIf { it.isNotBlank() }
                                ?: P2pNetworks.SEPOLIA.subgraphUrl,
                    )
                }

                else -> {
                    error(
                        "Unknown P2P_NETWORK build flag value '$net' — expected '${P2pNetworks.SEPOLIA_NAME}', " +
                            "'${P2pNetworks.MAINNET_NAME}', or blank for the default.",
                    )
                }
            }
        }
        single<P2pNetworkConfig> { get<P2pConfigProvider>().current() }
        single<BaseRpcClient> {
            val cfg = get<P2pNetworkConfig>()
            BaseRpcClient(httpClient = get(named(OFFRAMP_HTTP_CLIENT_QUALIFIER)), rpcUrl = cfg.rpcUrl)
        }
        single<SubgraphClient> {
            val cfg = get<P2pNetworkConfig>()
            SubgraphClient(httpClient = get(named(OFFRAMP_HTTP_CLIENT_QUALIFIER)), subgraphUrl = cfg.subgraphUrl)
        }
        // Dynamic-PIX amount resolver: native HTTP has no CORS wall, so DirectPixResolver fetches the
        // bank location endpoint straight from the device, no proxy needed.
        single<DynamicPixResolver> {
            DirectPixResolver(httpClient = get(named(OFFRAMP_HTTP_CLIENT_QUALIFIER)))
        }
        single<SeedPhraseSource> { WalletSeedPhraseSource(persistableWalletProvider = get()) }
        single<OfframpAccountProvider> {
            // CachingOfframpAccountProvider memoises the derived EvmKey so the mnemonic
            // crosses the SeedPhraseSource seam once per app lifetime, not once per order —
            // shrinks the in-heap secret footprint and skips the per-call PBKDF2 + BIP-44.
            if (BuildConfig.OFFRAMP_USE_DEV_KEY) {
                DevOfframpAccountProvider
            } else {
                CachingOfframpAccountProvider(StaticOfframpAccountProvider(seedPhraseSource = get()))
            }
        }
        single<BundlerClient> {
            val cfg = get<P2pNetworkConfig>()
            BundlerClient(
                httpClient = get(named(OFFRAMP_HTTP_CLIENT_QUALIFIER)),
                bundlerUrl = BundlerClient.urlFor(cfg.chainId, BuildConfig.PIMLICO_API_KEY),
                entryPoint = cfg.entryPointAddress,
                chainId = cfg.chainId,
                // Blank gradle property → null → Pimlico falls back to the project's default
                // sponsorship rules. Configure the policy in the Pimlico dashboard and set
                // PIMLICO_SPONSORSHIP_POLICY_ID in local.properties (or env var) to scope a
                // stolen-from-APK key's blast radius to just the Diamond + USDC selectors used
                // by the offramp flow.
                sponsorshipPolicyId = BuildConfig.PIMLICO_SPONSORSHIP_POLICY_ID.takeIf { it.isNotBlank() },
            )
        }
        single<OfframpBridgeWallet> {
            RealOfframpBridgeWallet(
                accountDataSource = get(),
                zashiProposalRepository = get(),
                keystoneProposalRepository = get(),
                submitProposal = get(),
                synchronizerProvider = get(),
            )
        }
        // One NearBridge instance backs funding, top-up, and preview on mainnet; testnet picks the
        // no-route alternatives below (only mainnet has a NEAR ZEC↔USDC route).
        single {
            NearBridgeOfframpFunding(
                rpc = get(),
                usdc = get<P2pNetworkConfig>().usdcAddress,
                swapDataSource = get(),
                wallet = get(),
            )
        }
        single<OfframpFunding> {
            if (get<P2pNetworkConfig>().chainId == P2pNetworks.MAINNET_CHAIN_ID) {
                get<NearBridgeOfframpFunding>()
            } else {
                PreFundedOfframpFunding(rpc = get(), usdc = get<P2pNetworkConfig>().usdcAddress)
            }
        }
        single<OfframpRefund> {
            val cfg = get<P2pNetworkConfig>()
            if (cfg.chainId == P2pNetworks.MAINNET_CHAIN_ID) {
                NearPullbackOfframpRefund(usdc = cfg.usdcAddress, swapDataSource = get(), wallet = get())
            } else {
                NoRouteOfframpRefund()
            }
        }
        single<OfframpTopUp> {
            if (get<P2pNetworkConfig>().chainId == P2pNetworks.MAINNET_CHAIN_ID) {
                get<NearBridgeOfframpFunding>()
            } else {
                NoRouteOfframpTopUp()
            }
        }
        single<OfframpTopUpPreview> {
            if (get<P2pNetworkConfig>().chainId == P2pNetworks.MAINNET_CHAIN_ID) {
                get<NearBridgeOfframpFunding>()
            } else {
                OfframpTopUpPreview { _, _ -> null }
            }
        }
        single {
            val cfg = get<P2pNetworkConfig>()
            SmartOfframpAccountProvider(
                accountProvider = get(),
                rpc = get(),
                accountFactory = cfg.accountFactoryAddress,
            )
        }

        single<(String, Throwable?) -> Unit>(named("offramp_warn")) {
            { msg, cause ->
                if (cause != null) Twig.warn(cause) { msg } else Twig.warn { msg }
            }
        }
    }
