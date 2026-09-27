package co.electriccoin.zcash.di

import android.os.SystemClock
import co.electriccoin.zcash.ui.common.invest.provider.IntentsSaltProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProviderImpl
import co.electriccoin.zcash.ui.common.invest.provider.InvestServerClock
import co.electriccoin.zcash.ui.common.invest.provider.KtorInvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepositoryImpl
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepositoryImpl
import co.electriccoin.zcash.ui.common.invest.repository.InvestSwapAssetSource
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/** Invest's data layer. Its network calls follow the user's Tor setting. */
val investModule =
    module {
        single { InvestServerClock() }
        singleOf(::KtorInvestApiProvider) bind InvestApiProvider::class
        single { IntentsSaltProvider(httpClientProvider = get(), serverClock = get()) }
        singleOf(::PrivateAccountKeyProvider)
        singleOf(::InvestSettingsRepositoryImpl) bind InvestSettingsRepository::class
        singleOf(::InvestBuyCheckpointStorageProviderImpl) bind InvestBuyCheckpointStorageProvider::class
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
