package co.electriccoin.zcash.di

import android.os.SystemClock
import co.electriccoin.zcash.ui.common.invest.provider.IntentsSaltProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestServerClock
import co.electriccoin.zcash.ui.common.invest.provider.KtorInvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Invest's data layer. Its network calls follow the user's Tor setting. */
val investModule =
    module {
        single { InvestServerClock() }
        singleOf(::KtorInvestApiProvider) bind InvestApiProvider::class
        single { IntentsSaltProvider(httpClientProvider = get(), serverClock = get()) }
        singleOf(::PrivateAccountKeyProvider)
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
