package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.common.provider.OrderRecipientUpiStorageProvider
import co.electriccoin.zcash.ui.common.provider.RelayIdentityStorageProvider
import co.electriccoin.zcash.ui.common.repository.ApplicationStateRepository
import co.electriccoin.zcash.ui.common.repository.ApplicationStateRepositoryImpl
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
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
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

val repositoryModule =
    module {
        singleOf(::WalletRepositoryImpl) bind WalletRepository::class
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
        singleOf(::LinkPreviewRepository)

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
                bundler = get(),
                network = get(),
                accountProvider = get(),
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
    }
