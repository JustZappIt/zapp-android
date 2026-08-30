@file:Suppress("DEPRECATION")

package co.electriccoin.zcash.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.compose.BindCompLocalProvider
import co.electriccoin.zcash.ui.common.compose.DisableScreenTimeout
import co.electriccoin.zcash.ui.common.extension.setContentCompat
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.provider.CHAT_CONVERSATION_ID_EXTRA
import co.electriccoin.zcash.ui.common.push.ChatNotificationTiming
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationUIState
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationViewModel
import co.electriccoin.zcash.ui.common.viewmodel.OldHomeViewModel
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.component.ConfigurationOverride
import co.electriccoin.zcash.ui.design.component.Override
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.ScreenTimeoutVM
import co.electriccoin.zcash.ui.screen.authentication.AuthenticationUseCase
import co.electriccoin.zcash.ui.screen.authentication.WrapAuthentication
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import co.electriccoin.zcash.ui.screen.gift.GiftClaimArgs
import co.electriccoin.zcash.ui.screen.gift.model.GIFT_LINK_HOST
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkIntake
import co.electriccoin.zcash.ui.screen.gift.model.PendingGiftLinkStore
import co.electriccoin.zcash.ui.screen.reputation.increase.ReclaimReturnLink
import co.electriccoin.zcash.ui.screen.scan.thirdparty.ThirdPartyScan
import co.electriccoin.zcash.ui.screen.splash.ZappSplashAnimation
import co.electriccoin.zcash.ui.screen.warning.viewmodel.StorageCheckViewModel
import co.electriccoin.zcash.work.WorkIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions")
class MainActivity : FragmentActivity() {
    private val oldHomeViewModel by viewModel<OldHomeViewModel>()

    val walletViewModel by viewModel<WalletViewModel>()

    val storageCheckViewModel by viewModel<StorageCheckViewModel>()

    internal val authenticationViewModel by viewModel<AuthenticationViewModel>()

    lateinit var navControllerForTesting: NavHostController

    val configurationOverrideFlow = MutableStateFlow<ConfigurationOverride?>(null)

    private val zappSplashStartFlow = MutableStateFlow(false)

    private val navigationRouter: NavigationRouter by inject()
    private val migrationAppHooks: MigrationAppHooks by inject()

    private val chatNotificationTiming: ChatNotificationTiming by inject()

    private val pendingGiftLinks: PendingGiftLinkStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Twig.debug { "Activity state: Create" }

        setAllowedScreenOrientation()

        setupSplashScreen()

        setupUiContent()

        monitorForBackgroundSync()

        forwardUriIntent(intent)
        forwardChatNotificationIntent(intent)
        handleMigrationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        forwardUriIntent(intent)
        forwardChatNotificationIntent(intent)
        handleMigrationIntent(intent)
    }

    /**
     * Routes an incoming URI, recognising gift links before the blanket forward to the scanner.
     *
     * A gift link is bearer money, so every rejection here is deliberate (§3.7). The URI is never
     * logged at any level, including error paths.
     */
    private fun forwardUriIntent(intent: Intent) {
        val data = intent.data ?: return

        // Recents re-delivers the original intent. Nothing here may act on it twice: for a gift
        // link that would re-enqueue a claim already on the back stack, and for anything else it
        // would reopen the scanner over whatever the user came back to.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return

        // The Verifier returning the user from a Reclaim session. Consumed and deliberately not
        // navigated: the verification screen is still up and still polling the session, so the
        // link's whole job is bringing the task forward — singleTask has already done that by the
        // time this runs. Forwarding anywhere would tear down a run in progress, and falling
        // through to the scanner below would put the camera over it.
        if (isReclaimReturnUri(intent, data)) {
            intent.data = null
            return
        }

        if (isGiftUri(intent, data)) openGiftClaim(intent, data) else navigationRouter.forward(ThirdPartyScan)
    }

    private fun isReclaimReturnUri(intent: Intent, data: Uri): Boolean =
        intent.action == Intent.ACTION_VIEW && ReclaimReturnLink.HOST.equals(data.host, ignoreCase = true)

    private fun openGiftClaim(intent: Intent, data: Uri) {
        val raw = intent.dataString ?: data.toString()
        // Consume once, exactly as the chat notification path does, so Activity recreation cannot
        // re-enqueue a claim that is already on the back stack.
        intent.data = null
        when (val intake = pendingGiftLinks.put(raw)) {
            is GiftLinkIntake.Accepted -> navigationRouter.forward(GiftClaimArgs(intake.token))

            // The claim this would open is already on its way in; a second screen for one card
            // would be two attempts to spend the same note.
            GiftLinkIntake.AlreadyPending -> Unit

            // Nothing to open, but the tap still has to land somewhere it can be explained.
            GiftLinkIntake.Refused -> navigationRouter.forward(GiftClaimArgs())
        }
    }

    private fun isGiftUri(intent: Intent, data: Uri): Boolean =
        intent.action == Intent.ACTION_VIEW && GIFT_LINK_HOST.equals(data.host, ignoreCase = true)

    private fun forwardChatNotificationIntent(intent: Intent) {
        intent.getStringExtra(CHAT_CONVERSATION_ID_EXTRA)?.let { conversationId ->
            // Consume once so Activity recreation cannot enqueue the same room again.
            intent.removeExtra(CHAT_CONVERSATION_ID_EXTRA)
            chatNotificationTiming.onNotificationTap(conversationId)
            navigationRouter.custom { current ->
                // Pushing the room we are already on stacks a duplicate entry, so the first
                // back press just reveals the same conversation.
                if (current.isChatRoomOf(conversationId)) {
                    null
                } else {
                    NavigationCommand.Forward(listOf(ChatRoomArgs(conversationId = conversationId)))
                }
            }
            chatNotificationTiming.onDeepLinkDispatched()
        }
    }

    private fun NavBackStackEntry?.isChatRoomOf(conversationId: String): Boolean {
        val destination = this?.destination ?: return false
        return destination.hasRoute<ChatRoomArgs>() &&
            toRoute<ChatRoomArgs>().conversationId == conversationId
    }

    private fun handleMigrationIntent(intent: Intent): Boolean = migrationAppHooks.handleIntent(intent, lifecycleScope)

    override fun onStart() {
        Twig.debug { "Activity state: Start" }
        authenticationViewModel.runAuthenticationRequiredCheck()
        checkMigrationRecoveryOnStart()
        super.onStart()
    }

    // RootNavGraph's secretState-driven redirect only re-fires when secretState changes
    // identity, so it won't catch "a transfer became overdue while backgrounded, already
    // unlocked." onStart() fires on every foreground transition and catches that case —
    // isSyncBlocked() has already stopped sync regardless, this is routing only.
    private fun checkMigrationRecoveryOnStart() {
        lifecycleScope.launch { migrationAppHooks.checkRecovery() }
    }

    override fun onStop() {
        Twig.debug { "Activity state: Stop" }
        authenticationViewModel.persistGoToBackgroundTime(System.currentTimeMillis())
        super.onStop()
    }

    /**
     * Sets whether the screen rotation is enabled or screen orientation is locked in the portrait mode.
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private fun setAllowedScreenOrientation() {
        requestedOrientation =
            if (BuildConfig.IS_SCREEN_ROTATION_ENABLED) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
    }

    private fun setupSplashScreen() {
        val splashScreen = installSplashScreen()
        val start = SystemClock.elapsedRealtime().milliseconds

        // Dismiss the system splash as soon as the first Compose frame is ready; the Zapp Z
        // animation (which covers the screen in white) takes over from there and plays while any
        // remaining wallet load finishes underneath it.
        splashScreen.setKeepOnScreenCondition {
            if (SPLASH_SCREEN_DELAY > Duration.ZERO) {
                val now = SystemClock.elapsedRealtime().milliseconds
                // This delay is for debug purposes only; do not enable for production usage.
                now - start < SPLASH_SCREEN_DELAY
            } else {
                false
            }
        }

        // Start the Z animation exactly when the system splash leaves, so the slide-in is never
        // hidden behind it.
        splashScreen.setOnExitAnimationListener { provider ->
            zappSplashStartFlow.value = true
            provider.remove()
        }
    }

    private fun setupUiContent() {
        // Turn off the decor fitting system windows, which allows us to handle insets,
        // including IME animations, and go edge-to-edge.
        // This also sets up the initial system bar style based on the platform theme
        enableEdgeToEdge()
        setContentCompat {
            Override(configurationOverrideFlow) {
                val isHideBalances by oldHomeViewModel.isHideBalances.collectAsStateWithLifecycle()
                ZcashTheme(
                    balancesAvailable = isHideBalances == false
                ) {
                    ProvideZappTheme {
                        val authState =
                            authenticationViewModel.appAccessAuthenticationResultState
                                .collectAsStateWithLifecycle()
                                .value
                        Box(Modifier.fillMaxSize()) {
                            BlankSurface(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .imePadding()
                            ) {
                                BindCompLocalProvider {
                                    MainContent()
                                    ScreenTimeoutHandle()
                                }
                            }

                            // Edge-to-edge brand splash overlay: the system splash hands off to the
                            // Z animation, which on finish removes itself to reveal the app. Kept
                            // outside BlankSurface (and its imePadding) so it covers the whole
                            // screen including the system bar areas.
                            var showZappSplash by rememberSaveable { mutableStateOf(true) }
                            if (showZappSplash) {
                                val startZappSplash by zappSplashStartFlow.collectAsStateWithLifecycle()
                                ZappSplashAnimation(
                                    start = startZappSplash,
                                    canFinish =
                                        authState == AuthenticationUIState.NotRequired ||
                                            authState == AuthenticationUIState.Successful,
                                    onFinished = { showZappSplash = false },
                                )
                            }

                            // App-access authentication must sit ABOVE the splash overlay. The system
                            // biometric prompt is a separate system window (always above the splash),
                            // but the in-app PIN pad is a Compose overlay — drawn under the splash it
                            // is invisible and untappable, so a PIN user is stranded on the splash
                            // forever (canFinish only flips on a successful auth). The lock screens are
                            // full-screen opaque, so the splash still keeps home content private until
                            // auth succeeds, then parts to reveal it.
                            AuthenticationForAppAccess(authState)
                        }
                    }
                }
            }

            // Force collection to improve performance; sync can start happening while
            // the user is going through the backup flow.
            walletViewModel.synchronizer.collectAsStateWithLifecycle()
        }
    }

    @Composable
    private fun AuthenticationForAppAccess(authState: AuthenticationUIState) {
        when (authState) {
            AuthenticationUIState.Initial -> {
                Twig.debug { "Authentication initial state" }
                // Wait for the state update
            }

            AuthenticationUIState.NotRequired -> {
                Twig.debug { "App access authentication NOT required" }
                // No action needed - the main app content is laid out now
            }

            is AuthenticationUIState.Required -> {
                Twig.debug { "App access authentication required" }

                // Show PIN directly, or trigger biometric app-access authentication with the
                // brand privacy screen behind the system prompt.
                WrapAuthentication(
                    onSuccess = {
                        authenticationViewModel.appAccessAuthentication.value = AuthenticationUIState.Successful
                    },
                    onCancel = {
                        authenticationViewModel.setAuthFailed()
                    },
                    onFail = {
                        authenticationViewModel.setAuthFailed()
                    },
                    useCase = AuthenticationUseCase.AppAccess,
                    authMethod = authState.authMethod,
                )
            }

            AuthenticationUIState.Successful -> {
                Twig.debug { "Authentication successful - entering the app" }
                // No action is needed - the main app content is laid out now
            }
        }
    }

    @Composable
    private fun MainContent() {
        val secretState by walletViewModel.secretState.collectAsStateWithLifecycle()
        RootNavGraph(secretState, walletViewModel, storageCheckViewModel)
    }

    @Composable
    private fun ScreenTimeoutHandle() {
        val vm = koinViewModel<ScreenTimeoutVM>()
        val isScreenTimeoutDisabled by vm.isScreenTimeoutDisabled.collectAsStateWithLifecycle()

        if (isScreenTimeoutDisabled == true) {
            DisableScreenTimeout()
        }
    }

    private fun monitorForBackgroundSync() {
        val isEnableBackgroundSyncFlow =
            run {
                val isSecretReadyFlow = walletViewModel.secretState.map { it == SecretState.READY }
                val isBackgroundSyncEnabledFlow = oldHomeViewModel.isBackgroundSyncEnabled.filterNotNull()

                isSecretReadyFlow.combine(isBackgroundSyncEnabledFlow) { isSecretReady, isBackgroundSyncEnabled ->
                    isSecretReady && isBackgroundSyncEnabled
                }
            }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                isEnableBackgroundSyncFlow.collect { isEnableBackgroundSync ->
                    if (isEnableBackgroundSync) {
                        WorkIds.enableBackgroundSynchronization(application)
                    } else {
                        WorkIds.disableBackgroundSynchronization(application)
                    }
                }
            }
        }
    }

    companion object {
        @VisibleForTesting
        internal val SPLASH_SCREEN_DELAY = 0.seconds
    }
}
