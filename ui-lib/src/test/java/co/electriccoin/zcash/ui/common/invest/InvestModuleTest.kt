package co.electriccoin.zcash.ui.common.invest

import co.electriccoin.zcash.di.investModule
import co.electriccoin.zcash.ui.common.invest.provider.IntentsSaltProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestServerClock
import co.electriccoin.zcash.ui.common.invest.provider.KtorInvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import xyz.justzappit.offramp.account.SeedPhraseSource
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class InvestModuleTest {
    @Test
    fun `every Invest binding resolves from its two outside dependencies`() {
        // providerModule supplies these two in the app; nothing else is needed.
        val outside =
            module {
                single<HttpClientProvider> { FakeHttpClientProvider { error("no network in this test") } }
                single { SeedPhraseSource { TEST_MNEMONIC.toCharArray() } }
            }
        val koin = koinApplication { modules(outside, investModule) }.koin

        assertIs<KtorInvestApiProvider>(koin.get<InvestApiProvider>())
        koin.get<IntentsSaltProvider>()
        koin.get<PrivateAccountKeyProvider>()
        koin.get<PrivateAccountSession>()
        // One clock, so every response corrects the time the session signs with.
        assertSame(koin.get<InvestServerClock>(), koin.get<InvestServerClock>())
    }
}
