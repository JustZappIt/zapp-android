package co.electriccoin.zcash.app

import androidx.lifecycle.ProcessLifecycleOwner
import co.electriccoin.zcash.crash.android.GlobalCrashReporter
import co.electriccoin.zcash.crash.android.di.CrashReportersProvider
import co.electriccoin.zcash.crash.android.di.crashProviderModule
import co.electriccoin.zcash.di.addressBookModule
import co.electriccoin.zcash.di.coreModule
import co.electriccoin.zcash.di.dataSourceModule
import co.electriccoin.zcash.di.mapperModule
import co.electriccoin.zcash.di.metadataModule
import co.electriccoin.zcash.di.providerModule
import co.electriccoin.zcash.di.repositoryModule
import co.electriccoin.zcash.di.useCaseModule
import co.electriccoin.zcash.di.viewModelModule
import co.electriccoin.zcash.di.zappMessagingModule
import co.electriccoin.zcash.migration.di.featureMigrationModule
import co.electriccoin.zcash.spackle.StrictModeCompat
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.CrashReportingStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.push.ChatPushBackend
import co.electriccoin.zcash.ui.common.repository.ApplicationStateRepository
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.repository.WalletSnapshotRepository
import co.electriccoin.zcash.ui.common.usecase.ObserveSeedMismatchUseCase
import co.electriccoin.zcash.ui.screen.chat.common.ChatBootstrap
import co.electriccoin.zcash.voting.di.featureVotingModule
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf

class ZcashApplication : CoroutineApplication() {
    private val flexaRepository by inject<FlexaRepository>()
    private val getAvailableCrashReporters: CrashReportersProvider by inject()
    private val homeMessageCacheRepository: HomeMessageCacheRepository by inject()
    private val walletSnapshotRepository: WalletSnapshotRepository by inject()
    private val crashReportingStorageProvider: CrashReportingStorageProvider by inject()
    private val applicationStateRepository: ApplicationStateRepository by inject {
        parametersOf(ProcessLifecycleOwner.get().lifecycle)
    }
    private val walletRepository: WalletRepository by inject()
    private val chatPushBackend: ChatPushBackend by inject()
    private val chatBootstrap: ChatBootstrap by inject()
    private val migrationNotifier: MigrationNotifier by inject()
    private val observeSeedMismatch: ObserveSeedMismatchUseCase by inject()

    override fun onCreate() {
        super.onCreate()

        configureGrpcHappyEyeballs()

        configureLogging()

        configureStrictMode()

        startKoin {
            androidLogger()
            androidContext(this@ZcashApplication)
            modules(
                coreModule,
                providerModule,
                crashProviderModule,
                dataSourceModule,
                repositoryModule,
                addressBookModule,
                metadataModule,
                useCaseModule,
                mapperModule,
                viewModelModule,
                zappMessagingModule,
                featureMigrationModule,
                featureVotingModule,
            )
        }

        chatPushBackend.initialize()

        // Since analytics will need disk IO internally, we want this to be registered after strict
        // mode is configured to ensure none of that IO happens on the main thread
        configureAnalytics()

        migrationNotifier.createChannel()
        flexaRepository.init()
        homeMessageCacheRepository.init()
        walletSnapshotRepository.init()
        applicationStateRepository.init()
        chatBootstrap.start()
        walletRepository.init()
        applicationScope.launch { observeSeedMismatch() }
    }

    /**
     * Makes gRPC fall back from a stalled IPv6 connection to IPv4 (RFC 8305, "Happy Eyeballs").
     *
     * A silently blackholed IPv6 path — common enough on home routers and carriers — leaves gRPC
     * sitting in `waiting_for_connection` until its 10s deadline expires, because the legacy
     * pick-first tries addresses strictly in order and the OS does not abandon a blackholed SYN
     * for well over a minute. Every reconnect starts with IPv6 again, so it never recovers.
     *
     * This is invisible to anything routed over Tor, which resolves at the exit — so it surfaces
     * on the one flow that cannot use Tor: a gift card's own clearnet synchronizer, which then
     * reports a perfectly good card as unreachable.
     *
     * Set before Koin and any channel construction, because gRPC reads these once at class load.
     *
     * Both flags are experimental in gRPC 1.78 (verified against that version): if a bump renames
     * or drops them this silently reverts to the old behaviour, which is why the values are logged.
     * The durable fix is Happy Eyeballs in the SDK's `ChannelFactory`.
     *
     * TODO [#0]: replace with an SDK-side fix and drop these flags; swap #0 for a real ticket.
     */
    private fun configureGrpcHappyEyeballs() {
        runCatching {
            System.setProperty("GRPC_EXPERIMENTAL_ENABLE_NEW_PICK_FIRST", "true")
            System.setProperty("GRPC_PF_USE_HAPPY_EYEBALLS", "true")
        }.onFailure { Twig.warn(it) { "gRPC Happy Eyeballs flags could not be set" } }

        Twig.info {
            "gRPC Happy Eyeballs: newPickFirst=" +
                System.getProperty("GRPC_EXPERIMENTAL_ENABLE_NEW_PICK_FIRST") +
                " happyEyeballs=" + System.getProperty("GRPC_PF_USE_HAPPY_EYEBALLS")
        }
    }

    private fun configureLogging() {
        Twig.initialize(applicationContext)
        Twig.info { "Starting application…" }

        if (!BuildConfig.DEBUG) {
            // In release builds, logs should be stripped by R8 rules
            Twig.assertLoggingStripped()
        }
    }

    private fun configureStrictMode() {
        if (BuildConfig.DEBUG) {
            StrictModeCompat.enableStrictMode(BuildConfig.IS_STRICT_MODE_CRASH_ENABLED)
        }
    }

    private fun configureAnalytics() {
        if (GlobalCrashReporter.register(this, getAvailableCrashReporters())) {
            applicationScope.launch {
                crashReportingStorageProvider.observe().collect {
                    Twig.debug { "Is crashlytics enabled: $it" }
                    if (it == true) {
                        GlobalCrashReporter.enable()
                    } else {
                        GlobalCrashReporter.disableAndDelete()
                    }
                }
            }
        }
    }
}
