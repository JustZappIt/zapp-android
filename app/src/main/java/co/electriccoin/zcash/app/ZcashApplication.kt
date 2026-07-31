package co.electriccoin.zcash.app

import android.content.Intent
import android.os.Process
import androidx.lifecycle.ProcessLifecycleOwner
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
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
import co.electriccoin.zcash.spackle.StrictModeCompat
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.CrashReportingStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.push.ChatPushBackend
import co.electriccoin.zcash.ui.common.repository.ApplicationStateRepository
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.repository.WalletSnapshotRepository
import co.electriccoin.zcash.ui.screen.chat.common.ChatBootstrap
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val synchronizerProvider: SynchronizerProvider by inject()
    private val navigateToError: NavigateToErrorUseCase by inject()
    private val chatPushBackend: ChatPushBackend by inject()
    private val chatBootstrap: ChatBootstrap by inject()

    override fun onCreate() {
        super.onCreate()

        installSeedMismatchHandler()

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
            )
        }

        chatPushBackend.initialize()

        // Since analytics will need disk IO internally, we want this to be registered after strict
        // mode is configured to ensure none of that IO happens on the main thread
        configureAnalytics()

        flexaRepository.init()
        homeMessageCacheRepository.init()
        walletSnapshotRepository.init()
        applicationStateRepository.init()
        chatBootstrap.start()
        walletRepository.init()
        observeSynchronizerError()
    }

    /**
     * Installs a global uncaught-exception handler that recovers from [InitializeException.SeedNotRelevant].
     *
     * This exception is thrown by the Zcash SDK (v2.4.8) inside [WalletCoordinator.walletScope]
     * (Dispatchers.Main, no SupervisorJob) when the seed stored in encrypted prefs doesn't match
     * the existing on-disk database — typically after a reinstall or partial data wipe.  Because
     * the SDK scope has no CoroutineExceptionHandler the exception escapes to the main thread's
     * uncaught-exception handler and crashes the process before our UI can react.
     *
     * Recovery: erase the mismatched SDK databases via [Synchronizer.erase], then restart the
     * app cleanly so the user lands on the onboarding flow.
     */
    private fun installSeedMismatchHandler() {
        val upstream = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (throwable.isSeedNotRelevant()) {
                Twig.error { "SeedNotRelevant — erasing mismatched SDK data and restarting" }
                try {
                    val network = ZcashNetwork.fromResources(applicationContext)
                    runBlocking { Synchronizer.erase(applicationContext, network) }
                } catch (e: Exception) {
                    Twig.error(e) { "Failed to erase SDK data during seed-mismatch recovery" }
                }
                packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                    startActivity(intent)
                }
                Process.killProcess(Process.myPid())
            } else {
                upstream?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun Throwable.isSeedNotRelevant(): Boolean =
        this is InitializeException.SeedNotRelevant ||
            cause?.isSeedNotRelevant() == true ||
            suppressed.any { it.isSeedNotRelevant() }

    private fun observeSynchronizerError() {
        applicationScope.launch {
            synchronizerProvider.synchronizer
                .map { it?.initializationError }
                .collect {
                    if (it == Synchronizer.InitializationError.TOR_NOT_AVAILABLE) {
                        navigateToError(ErrorArgs.SynchronizerTorInitError)
                    }
                }
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
