package co.electriccoin.zcash.di

import android.os.SystemClock
import co.electriccoin.zcash.ui.common.invest.provider.IntentsSaltProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProviderImpl
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProviderImpl
import co.electriccoin.zcash.ui.common.invest.provider.InvestServerClock
import co.electriccoin.zcash.ui.common.invest.provider.KtorInvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepositoryImpl
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellRepositoryImpl
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepositoryImpl
import co.electriccoin.zcash.ui.common.invest.repository.InvestSwapAssetSource
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import kotlin.time.Instant

/** Invest's data layer. Its network calls follow the user's Tor setting. */
val investModule =
    module {
        single { InvestServerClock() }
        singleOf(::KtorInvestApiProvider) bind InvestApiProvider::class
        single { IntentsSaltProvider(httpClientProvider = get(), serverClock = get()) }
        singleOf(::PrivateAccountKeyProvider)
        singleOf(::InvestSettingsRepositoryImpl) bind InvestSettingsRepository::class
        singleOf(::InvestBuyCheckpointStorageProviderImpl) bind InvestBuyCheckpointStorageProvider::class
        singleOf(::InvestSellCheckpointStorageProviderImpl) bind InvestSellCheckpointStorageProvider::class
        single {
            InvestSellRepositoryImpl(
                api = get(),
                session = get(),
                keys = get(),
                wallet = get(),
                investRepository = get(),
                swapAssets = get(),
                biometricRepository = get(),
                checkpoints = get(),
                now = get<InvestServerClock>().let { clock -> { Instant.fromEpochMilliseconds(clock.nowMillis()) } },
            )
        } bind InvestSellRepository::class
        single {
            InvestRepositoryImpl(
                api = get(),
                session = get(),
                keys = get(),
                wallet = get(),
                accountDataSource = get(),
                swapAssetProvider = get(),
                synchronizerProvider = get(),
                checkpoints = get(),
                now = get<InvestServerClock>().let { clock -> { Instant.fromEpochMilliseconds(clock.nowMillis()) } },
            )
        } binds arrayOf(InvestRepository::class, InvestSwapAssetSource::class)
        single {
            PrivateAccountSession(
                api = get(),
                salts = get(),
                clock = get(),
                keys = get(),
                elapsedMillis = SystemClock::elapsedRealtime,
            )
        }
    }
