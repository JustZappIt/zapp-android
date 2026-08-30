package co.electriccoin.zcash.di

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.pricing.repository.HistoricalPriceRepository
import co.electriccoin.zcash.ui.common.pricing.repository.HistoricalPriceRepositoryImpl
import co.electriccoin.zcash.ui.common.provider.OrderRecipientUpiStorageProvider
import co.electriccoin.zcash.ui.common.provider.RelayIdentityStorageProvider
import co.electriccoin.zcash.ui.common.repository.ApplicationStateRepository
import co.electriccoin.zcash.ui.common.repository.ApplicationStateRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.AutomaticServerRepository
import co.electriccoin.zcash.ui.common.repository.AutomaticServerRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.BaseBalanceRepository
import co.electriccoin.zcash.ui.common.repository.BaseBalanceRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ConfigurationRepository
import co.electriccoin.zcash.ui.common.repository.ConfigurationRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.EphemeralAddressRepository
import co.electriccoin.zcash.ui.common.repository.EphemeralAddressRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.FlexaRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.MockOrchardBalanceRepository
import co.electriccoin.zcash.ui.common.repository.MockOrchardBalanceRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepository
import co.electriccoin.zcash.ui.common.repository.PeerCashOutRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.TransactionFilterRepository
import co.electriccoin.zcash.ui.common.repository.TransactionFilterRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.TransactionRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.repository.WalletRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.WalletSnapshotRepository
import co.electriccoin.zcash.ui.common.repository.WalletSnapshotRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.screen.chat.linkpreview.LinkPreviewRepository
import co.electriccoin.zcash.ui.screen.reputation.increase.ReclaimReturnLink
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.onramp.DirectOnrampDriver
import xyz.justzappit.offramp.onramp.FakeOnrampDriver
import xyz.justzappit.offramp.onramp.OnrampDriver
import xyz.justzappit.offramp.onramp.OnrampScreeningClient
import xyz.justzappit.offramp.onramp.OnrampScreeningConfig
import xyz.justzappit.offramp.orchestrator.AaOfframpDriver
import xyz.justzappit.offramp.orchestrator.OfframpDriver
import xyz.justzappit.offramp.p2p.CircleRouter
import xyz.justzappit.offramp.p2p.FallbackOrderReader
import xyz.justzappit.offramp.p2p.OnChainOrderReader
import xyz.justzappit.offramp.p2p.OrderReadSource
import xyz.justzappit.offramp.p2p.OrderRecipientUpiCache
import xyz.justzappit.offramp.p2p.P2pOrderHistorySource
import xyz.justzappit.offramp.p2p.RelayIdentityStore
import xyz.justzappit.offramp.p2p.SubgraphOrderReader
import xyz.justzappit.offramp.p2p.getUsdcBalance
import xyz.justzappit.offramp.reclaim.ReclaimAppCredentials
import xyz.justzappit.offramp.reclaim.ReclaimPoller
import xyz.justzappit.offramp.reclaim.ReclaimSessionMinter
import xyz.justzappit.offramp.reclaim.ReclaimVerificationDriver
import xyz.justzappit.offramp.reputation.ReputationReader
import java.util.Locale

val repositoryModule =
    module {
        singleOf(::WalletRepositoryImpl) bind WalletRepository::class
        singleOf(::AutomaticServerRepositoryImpl) bind AutomaticServerRepository::class
        singleOf(::ConfigurationRepositoryImpl) bind ConfigurationRepository::class
        singleOf(::ExchangeRateRepositoryImpl) bind ExchangeRateRepository::class
        singleOf(::FlexaRepositoryImpl) bind FlexaRepository::class
        singleOf(::BiometricRepositoryImpl) bind BiometricRepository::class
        singleOf(::KeystoneProposalRepositoryImpl) bind KeystoneProposalRepository::class
        singleOf(::TransactionRepositoryImpl) bind TransactionRepository::class
        singleOf(::TransactionFilterRepositoryImpl) bind TransactionFilterRepository::class
        singleOf(::ZashiProposalRepositoryImpl) bind ZashiProposalRepository::class
        singleOf(::HomeMessageCacheRepositoryImpl) bind HomeMessageCacheRepository::class
        singleOf(::WalletSnapshotRepositoryImpl) bind WalletSnapshotRepository::class
        singleOf(::ApplicationStateRepositoryImpl) bind ApplicationStateRepository::class
        singleOf(::SwapRepositoryImpl) bind SwapRepository::class
        singleOf(::EphemeralAddressRepositoryImpl) bind EphemeralAddressRepository::class
        singleOf(::MockOrchardBalanceRepositoryImpl) bind MockOrchardBalanceRepository::class
        singleOf(::LinkPreviewRepository)
        singleOf(::HistoricalPriceRepositoryImpl) bind HistoricalPriceRepository::class
        single<BaseBalanceRepository> {
            val rpc = get<BaseRpcClient>()
            val network = get<P2pNetworkConfig>()
            val accountProvider = get<SmartOfframpAccountProvider>()
            BaseBalanceRepositoryImpl(
                reader = { rpc.getUsdcBalance(network.usdcAddress, accountProvider.resolve().address) },
                applicationStateProvider = get(),
            )
        }
        // Constructed manually because its dispatcher is a default rather than a binding.
        single<PeerCashOutRepository> {
            PeerCashOutRepositoryImpl(
                orchestrator = get(),
                checkpointStorage = get(),
                payeeHandleProvider = get(),
            )
        }

        // UPI offramp data sources + orchestrator.
        single { CircleRouter() }
        single { SubgraphOrderReader(subgraph = get()) }
        single { OnChainOrderReader(rpc = get(), network = get()) }
        single<OrderReadSource> {
            FallbackOrderReader(
                primary = get<SubgraphOrderReader>(),
                fallback = get<OnChainOrderReader>(),
                logger = { msg, cause ->
                    val warn: (String, Throwable?) -> Unit = get(named("offramp_warn"))
                    warn(msg, cause)
                },
            )
        }
        single<RelayIdentityStore> { RelayIdentityStorageProvider(encryptedPreferenceProvider = get()) }
        single<OrderRecipientUpiCache> {
            OrderRecipientUpiStorageProvider(encryptedPreferenceProvider = get())
        }
        single {
            P2pOrderHistorySource(
                subgraph = get(),
                relayIdentityStore = get(),
                orderRecipientUpiCache = get(),
                onChainOrderReader = get<OnChainOrderReader>(),
                rpc = get(),
                network = get(),
            )
        }
        factory<OfframpDriver> {
            AaOfframpDriver(
                rpc = get(),
                network = get(),
                submitters = get(),
                subgraph = get(),
                orderReader = get(),
                funding = get(),
                refund = get(),
                topUp = get(),
                router = get(),
                relayIdentityStore = get(),
                orderRecipientUpiCache = get(),
            )
        }
        single { ReputationReader(rpc = get(), network = get()) }
        single {
            ReclaimAppCredentials(
                appId = BuildConfig.RECLAIM_APP_ID,
                appSecret = BuildConfig.RECLAIM_APP_SECRET,
            )
        }
        // No server sits in this path: sessions are minted on the device and the proof goes
        // straight from Reclaim to the ReputationManager. The Reclaim API is a third-party host,
        // not our RPC, but it shares the offramp client for its logging and retry behaviour.
        single {
            ReclaimVerificationDriver(
                minter =
                    ReclaimSessionMinter(
                        httpClient = get(named(OFFRAMP_HTTP_CLIENT_QUALIFIER)),
                        credentials = get(),
                        nowMillis = System::currentTimeMillis,
                        redirectUrl = ReclaimReturnLink.URL,
                    ),
                poller = ReclaimPoller(httpClient = get(named(OFFRAMP_HTTP_CLIENT_QUALIFIER))),
                submitters = get(),
                reputationReader = get(),
                rpc = get(),
                network = get(),
                credentials = get(),
                onUnrecognisedRevert = { selector ->
                    Twig.warn { "Reclaim socialVerify reverted with an unmapped selector: $selector" }
                },
            )
        }
        single {
            OnrampScreeningConfig(
                apiUrl = BuildConfig.P2P_SCREENING_API_URL,
                encryptionKeyHex = BuildConfig.P2P_SCREENING_KEY,
            )
        }
        // A BUY is placed by the user's own smart account, on chain. The operator service that
        // used to place them is gone; what remains of it is the shared CustodialOnrampDriver, which
        // this app no longer builds and only the iOS framework still uses.
        factory<OnrampDriver> {
            if (BuildConfig.DEBUG && BuildConfig.P2P_ONRAMP_USE_FAKE_DRIVER) {
                FakeOnrampDriver()
            } else {
                val screeningConfig: OnrampScreeningConfig = get()
                DirectOnrampDriver(
                    rpc = get(),
                    network = get(),
                    submitters = get(),
                    accountProvider = get(),
                    subgraph = get(),
                    // The chain, not the indexer: the subgraph returns `encUpi` empty, and that
                    // field is the entire payment step.
                    orderReader = get<OnChainOrderReader>(),
                    screening =
                        screeningConfig
                            .takeIf { it.isConfigured }
                            ?.let {
                                OnrampScreeningClient(
                                    httpClient = get(named(OFFRAMP_HTTP_CLIENT_QUALIFIER)),
                                    config = it,
                                    deviceSignals = get(),
                                    screeningSession = get(),
                                    nowMillis = System::currentTimeMillis,
                                )
                            },
                    relayIdentityStore = get(),
                    orderRecipientUpiCache = get(),
                    // Best-effort, and only ever a hint to the screening service: the device's
                    // region says where the phone was set up, which is usually but not always
                    // where its owner is buying.
                    country = Locale.getDefault().country.takeIf { it.isNotBlank() },
                    nowMillis = System::currentTimeMillis,
                )
            }
        }
    }
