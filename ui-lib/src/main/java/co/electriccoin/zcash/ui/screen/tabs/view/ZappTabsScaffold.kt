package co.electriccoin.zcash.ui.screen.tabs.view

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.NavigateToVotingUseCase
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.common.ChatBootstrap
import co.electriccoin.zcash.ui.screen.chat.identity.ChatIdentitySetupScreen
import co.electriccoin.zcash.ui.screen.chat.identity.ChatIdentitySetupVM
import co.electriccoin.zcash.ui.screen.chat.list.ChatListScreen
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository
import co.electriccoin.zcash.ui.screen.onboarding.ZappOnboardingFlow
import co.electriccoin.zcash.ui.screen.onboarding.ZappRestoreFlow
import co.electriccoin.zcash.ui.screen.tabs.TabsVM
import co.electriccoin.zcash.ui.screen.welcome.WelcomeGateVM
import co.electriccoin.zcash.ui.screen.welcome.view.WelcomeGateView
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
internal fun ZappTabsScaffold(
    navigationRouter: NavigationRouter,
) {
    val welcomeGateVM: WelcomeGateVM = koinViewModel()
    val walletViewModel: WalletViewModel = koinViewModel()
    val isWelcomeDismissed by welcomeGateVM.isWelcomeDismissed.collectAsState()
    val isOnboardingCompleted by welcomeGateVM.isOnboardingCompleted.collectAsState()
    val secretState by walletViewModel.secretState.collectAsState()

    // True while the user is filling out the chat-restore form (entered via
    // WelcomeGate's "I already use Zapp"). Held locally so cancelling drops
    // them back at the welcome gate without persisting any state.
    var restoreMode by rememberSaveable { mutableStateOf(false) }

    // Keep the restore completion screen mounted until both durable gates have updated.
    // Clearing restoreMode immediately could flash the welcome/onboarding UI for a frame
    // between its two asynchronous preference writes.
    LaunchedEffect(restoreMode, isWelcomeDismissed, isOnboardingCompleted) {
        if (restoreMode && isWelcomeDismissed == true && isOnboardingCompleted == true) {
            restoreMode = false
        }
    }

    // "I already use Zapp" deliberately keeps Welcome undismissed until the very end.
    // If the task is removed after its wallet commits, local restoreMode is gone but the
    // durable READY wallet proves that returning to Welcome would offer an unsafe re-restore.
    val resumeCommittedRestore = isWelcomeDismissed == false && secretState == SecretState.READY

    when {
        isWelcomeDismissed == null || isOnboardingCompleted == null -> {
            Box(modifier = Modifier.fillMaxSize()) // brief blank while prefs load
        }

        restoreMode || resumeCommittedRestore -> {
            ZappRestoreFlow(
                onComplete = {
                    welcomeGateVM.dismissWelcome()
                    welcomeGateVM.completeOnboarding()
                },
                onBackToWelcome = { restoreMode = false },
                walletViewModel = walletViewModel,
                chatBootstrap = koinInject(),
            )
        }

        isWelcomeDismissed == false -> {
            WelcomeGateView(
                onGetStarted = { welcomeGateVM.dismissWelcome() },
                onRestoreExisting = { restoreMode = true },
            )
        }

        isOnboardingCompleted == false -> {
            ZappOnboardingFlow(
                onComplete = { welcomeGateVM.completeOnboarding() },
                onBackToWelcome = { welcomeGateVM.undoDismissWelcome() },
                walletViewModel = walletViewModel,
                chatBootstrap = koinInject(),
                navigationRouter = navigationRouter,
            )
        }

        else -> {
            ZappTabsScaffoldContent()
        }
    }
}

@Composable
private fun ZappTabsScaffoldContent() {
    val tabsVM: TabsVM = koinViewModel()
    var currentTab by rememberSaveable { mutableStateOf(ZappTab.CHATS) }
    val localCurrency by tabsVM.localCurrency.collectAsState()
    val p2pPaymentMethod by tabsVM.p2pPaymentMethod.collectAsState()
    val hasPeerActivity by tabsVM.hasPeerActivity.collectAsState()
    // Set by tab content when it pushes a fullscreen sub-screen that owns its
    // own bottom CTA (e.g. wallet seed-reveal). Hides the floating nav pill so
    // the two don't overlap.
    var hideNavPill by rememberSaveable { mutableStateOf(false) }

    val chatConversationsRepository: ChatConversationsRepository = koinInject()
    val navigateToVoting: NavigateToVotingUseCase = koinInject()
    val scope = rememberCoroutineScope()
    val conversations by chatConversationsRepository.conversations.collectAsState()
    val unreadCount = conversations.orEmpty().sumOf { it.unreadCount }
    val c = ZappTheme.colors

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .semantics { testTagsAsResourceId = true },
    ) {
        // Fade-through, not a slide: tab switches are lateral moves, not navigation.
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                fadeIn(tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing))
                    .togetherWith(fadeOut(tween(ZappMotion.STATE_MS)))
            },
            label = "tabContent",
        ) { tab ->
            when (tab) {
                ZappTab.PAY -> {
                    WalletTabContent(
                        onRestoreWallet = tabsVM::onRestoreWalletClick,
                        onFullscreenChange = { hideNavPill = it },
                    )
                }

                ZappTab.CHATS -> {
                    ChatsTabContent()
                }

                ZappTab.YOU -> {
                    SettingsTabContent(
                        onChatProfileClick = tabsVM::onChatProfileClick,
                        onContactsClick = tabsVM::onContactsClick,
                        onAppLockClick = tabsVM::onAppLockClick,
                        localCurrency = localCurrency,
                        onLocalCurrencyClick = tabsVM::onLocalCurrencyClick,
                        p2pPaymentMethod = p2pPaymentMethod,
                        hasPeerActivity = hasPeerActivity,
                        onChooseServerClick = tabsVM::onChooseServerClick,
                        onTorClick = tabsVM::onTorClick,
                        onChatSettingsClick = tabsVM::onChatSettingsClick,
                        onCopyPublicKeyClick = tabsVM::onCopyPublicKeyClick,
                        onP2pPaymentMethodClick = tabsVM::onP2pPaymentMethodClick,
                        onBaseAccountClick = tabsVM::onBaseAccountClick,
                        onPortfolioChartClick = tabsVM::onPortfolioChartClick,
                        onViewingKeyExportClick = tabsVM::onViewingKeyExportClick,
                        onHardwareWalletClick = tabsVM::onHardwareWalletClick,
                        onVotingClick =
                            if (navigateToVoting.isEnabled) {
                                { scope.launch { navigateToVoting() } }
                            } else {
                                null
                            },
                    )
                }
            }
        }

        if (!hideNavPill) {
            FloatingPillNavBar(
                currentTab = currentTab,
                chatUnreadCount = unreadCount,
                onTabSelected = { selectedTab ->
                    if (selectedTab == ZappTab.PAY && currentTab != ZappTab.PAY) {
                        BalanceChartReadinessTrace.begin()
                    }
                    currentTab = selectedTab
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun ChatsTabContent() {
    val bootstrap: ChatBootstrap = koinInject()
    val identitySetupVm: ChatIdentitySetupVM = koinViewModel()
    val isInitializing by bootstrap.isInitializing.collectAsState()
    val isSetupComplete by identitySetupVm.isSetupComplete.collectAsState()

    val c = ZappTheme.colors
    when {
        isInitializing -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
        }

        !isSetupComplete -> {
            ChatIdentitySetupScreen()
        }

        else -> {
            ChatListScreen(
                showBackButton = false,
            )
        }
    }
}
