package co.electriccoin.zcash.ui.common.invest

import co.electriccoin.zcash.di.investModule
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.provider.IntentsSaltProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestSellCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestServerClock
import co.electriccoin.zcash.ui.common.invest.provider.KtorInvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSellRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSettingsRepository
import co.electriccoin.zcash.ui.common.invest.repository.InvestSwapAssetSource
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeFollower
import co.electriccoin.zcash.ui.common.invest.repository.InvestTradeGuard
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.common.provider.OfframpBridgeWallet
import co.electriccoin.zcash.ui.common.provider.SwapAssetProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import io.mockk.mockk
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

    @Test
    fun `the repositories and their storage resolve with the app's other modules`() {
        // Stands in for what the app's other modules provide; every Invest binding must resolve against it.
        val outside =
            module {
                single<HttpClientProvider> { FakeHttpClientProvider { error("no network in this test") } }
                single { SeedPhraseSource { TEST_MNEMONIC.toCharArray() } }
                single { mockk<EncryptedPreferenceProvider>(relaxed = true) }
                single { mockk<OfframpBridgeWallet>(relaxed = true) }
                single { mockk<AccountDataSource>(relaxed = true) }
                single { mockk<SwapAssetProvider>(relaxed = true) }
                single { mockk<SynchronizerProvider>(relaxed = true) }
                single { mockk<BiometricRepository>(relaxed = true) }
            }
        val koin = koinApplication { modules(outside, investModule) }.koin

        koin.get<InvestBuyCheckpointStorageProvider>()
        koin.get<InvestSellCheckpointStorageProvider>()
        koin.get<InvestSettingsRepository>()
        koin.get<InvestTradeGuard>()
        assertSame<Any>(koin.get<InvestRepository>(), koin.get<InvestSwapAssetSource>())
        koin.get<InvestSellRepository>()
        koin.get<InvestTradeFollower>()
    }
}
