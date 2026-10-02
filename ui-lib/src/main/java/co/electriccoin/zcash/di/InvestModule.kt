package co.electriccoin.zcash.di

import android.os.SystemClock
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.invest.demo.DemoInvestEngine
import co.electriccoin.zcash.ui.common.invest.demo.DemoInvestSellRepository
import co.electriccoin.zcash.ui.common.invest.demo.DemoWallet
import co.electriccoin.zcash.ui.common.invest.demo.InvestDemoControls
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
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeFollower
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeFollowerImpl
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeGuard
import org.koin.core.module.dsl.singleOf
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import kotlin.time.Instant

/**
 * Invest's data layer. Its network calls follow the user's Tor setting. A debug build made with `ZAPP_INVEST_DEMO`
 * binds the demo engine in place of the real one, so nothing reaches 1Click and no ZEC is sent.
 */
val investModule =
    module {
        single { InvestServerClock() }
        singleOf(::KtorInvestApiProvider) bind InvestApiProvider::class
        single { IntentsSaltProvider(httpClientProvider = get(), serverClock = get()) }
        singleOf(::PrivateAccountKeyProvider)
        singleOf(::InvestSettingsRepositoryImpl) bind InvestSettingsRepository::class
        // Not singleOf: its defaulted prefKey would be asked of Koin as a String.
        single { InvestBuyCheckpointStorageProviderImpl(encryptedPreferenceProvider = get()) } bind
            InvestBuyCheckpointStorageProvider::class
        singleOf(::InvestSellCheckpointStorageProviderImpl) bind InvestSellCheckpointStorageProvider::class
        single { InvestTradeGuard(buys = get(), sells = get()) }
        if (BuildConfig.IS_INVEST_DEMO) {
            single { InvestDemoControls() }
            single { DemoWallet() }
            single {
                DemoInvestEngine(
                    accountDataSource = get(),
                    swapRepository = get(),
                    settings = get(),
                    controls = get(),
                    wallet = get(),
                )
            } binds arrayOf(InvestRepository::class, InvestSwapAssetSource::class, InvestTradeFollower::class)
            single<InvestSellRepository> { DemoInvestSellRepository(engine = get(), controls = get()) }
        } else {
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
                    trades = get(),
                    now = serverNow(),
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
                    trades = get(),
                    settings = get(),
                    now = serverNow(),
                )
            } binds arrayOf(InvestRepository::class, InvestSwapAssetSource::class)
            single<InvestTradeFollower> { InvestTradeFollowerImpl(buys = get(), sells = get()) }
        }
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

// The engine's clock: 1Click's time once it has been seen, the phone's until then.
private fun Scope.serverNow(): () -> Instant {
    val clock = get<InvestServerClock>()
    return { Instant.fromEpochMilliseconds(clock.nowMillis()) }
}
