package co.electriccoin.zcash.ui.screen.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.enumSaver
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.chat.common.ChatBootstrap
import co.electriccoin.zcash.ui.screen.onboarding.view.BioScanScreen
import co.electriccoin.zcash.ui.screen.onboarding.view.MessagingPhaseIntro
import co.electriccoin.zcash.ui.screen.onboarding.view.OnboardingDoneScreen
import co.electriccoin.zcash.ui.screen.onboarding.view.PinSetupScreen
import co.electriccoin.zcash.ui.screen.onboarding.view.TwoFAChoiceScreen
import co.electriccoin.zcash.ui.screen.onboarding.view.TwoFAMode
import co.electriccoin.zcash.ui.screen.onboarding.view.UsernameEntryScreen
import co.electriccoin.zcash.ui.screen.onboarding.view.WalletChoiceScreen
import co.electriccoin.zcash.ui.screen.onboarding.view.WalletEncryptingScreen
import co.electriccoin.zcash.ui.screen.onboarding.view.WalletPhaseIntro
import co.electriccoin.zcash.ui.screen.onboarding.view.WalletSeedPhraseScreen
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedArgs
import org.koin.androidx.compose.koinViewModel

/**
 * Swiss-design post-welcome onboarding orchestrator.
 *
 * Runs after [co.electriccoin.zcash.ui.screen.welcome.view.WelcomeGateView] is
 * dismissed and before the user reaches the tabs shell. Three phases mirror the
 * design canvas:
 * - **Part 1 — Wallet** (intro, create/restore, seed)
 * - **Part 2 — Messaging account** (intro, username)
 * - **Part 3 — Secure Zapp** (biometric/PIN, scan, done)
 *
 * The wallet comes first because the messaging identity is *derived from* its
 * 24-word BIP-39 seed. Only once the wallet is provisioned ([OnboardingStep.WALLET_SEED] for
 * create, or the restore sub-flow returning READY) do we collect the username in
 * [OnboardingStep.MSG_USERNAME] and hand it to [ChatBootstrap.setPendingDisplayName]; the
 * reactive coroutine inside [ChatBootstrap] then derives the identity from the
 * now-present seed while [OnboardingStep.DERIVING] shows a spinner.
 */
@Composable
internal fun ZappOnboardingFlow(
    onComplete: () -> Unit,
    onBackToWelcome: () -> Unit,
    walletViewModel: WalletViewModel,
    chatBootstrap: ChatBootstrap,
    navigationRouter: NavigationRouter,
) {
    var step by rememberSaveable(stateSaver = enumSaver<OnboardingStep>()) {
        mutableStateOf(OnboardingStep.WALLET_INTRO)
    }
    var twoFAMode by rememberSaveable(stateSaver = enumSaver<TwoFAMode>()) { mutableStateOf(TwoFAMode.Bio) }
    var pendingUsername by rememberSaveable { mutableStateOf("") }
    var restoreReturnPending by rememberSaveable { mutableStateOf(false) }

    val walletSeed by walletViewModel.currentSeedWords.collectAsStateWithLifecycle()
    val secretState by walletViewModel.secretState.collectAsStateWithLifecycle()
    val walletProvisioningError by walletViewModel.walletProvisioningError.collectAsStateWithLifecycle()
    val chatIdentity by chatBootstrap.identity.collectAsStateWithLifecycle()

    val securityVM: OnboardingSecurityVM = koinViewModel()
    val bioState by securityVM.bioState.collectAsStateWithLifecycle()
    val pinSaved by securityVM.pinSaved.collectAsStateWithLifecycle()

    // Process-death recovery: rememberSaveable restores `pendingUsername`, but the
    // in-process `pendingDisplayName` inside ChatBootstrap dies with the process.
    // Re-publish once per rehydration. Keyed on `pendingUsername` alone (NOT `step`)
    // so step transitions don't re-fire this; `setPendingDisplayName` is idempotent
    // for the same name, but re-firing on every step change risked masking transient
    // state if its semantics ever drift.
    LaunchedEffect(pendingUsername) {
        if (pendingUsername.isNotBlank()) {
            chatBootstrap.setPendingDisplayName(pendingUsername)
        }
    }

    // Wallet-ready transitions. The username (and the identity derived from the seed) is
    // collected AFTER the wallet exists, so READY routes into the messaging phase rather
    // than straight to security.
    // - WALLET_CHOICE: the restore sub-flow persisted a wallet and popped back here
    //   (navigationRouter.backToRoot); move on to the messaging phase.
    // - WALLET_CREATE: the create path finished provisioning the wallet; advance to the
    //   seed-phrase reveal. Keyed as its own step (not WALLET_CHOICE) so create routes to
    //   the seed screen while restore routes to the messaging phase.
    LaunchedEffect(secretState, step, restoreReturnPending) {
        if (secretState == SecretState.READY) {
            step.walletReadyTarget(isRestoreReturn = restoreReturnPending)?.let { target ->
                step = target
                restoreReturnPending = false
            }
        }
    }

    // Chat-identity gate. Once the username is set, ChatBootstrap derives the identity
    // from the persisted seed; advance when it lands. A derive failure keeps the user on
    // DERIVING (which shows the error + retry) instead of advancing.
    LaunchedEffect(chatIdentity, step) {
        if (
            chatIdentity != null &&
            step.isMessagingGate()
        ) {
            step = OnboardingStep.SECURE_CHOICE
        }
    }

    // Advance to Done once biometric enrollment succeeds. Success stays silent here —
    // the Done screen fires its own Confirm pulse; doubling up would buzz twice.
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(bioState) {
        if (bioState is OnboardingSecurityVM.BioState.Success && step == OnboardingStep.BIO_SCAN) {
            step = OnboardingStep.DONE
        }
        if (bioState is OnboardingSecurityVM.BioState.Error && step == OnboardingStep.BIO_SCAN) {
            runCatching { haptic.performHapticFeedback(HapticFeedbackType.Reject) }
        }
    }

    // Advance to Done once PIN is saved.
    LaunchedEffect(pinSaved) {
        if (pinSaved && step == OnboardingStep.PIN_SETUP) {
            step = OnboardingStep.DONE
        }
    }

    val persistedAuthMethod by securityVM.authMethod.collectAsStateWithLifecycle()
    LaunchedEffect(persistedAuthMethod, step) {
        val recoveredMode = persistedAuthMethod?.toTwoFAMode()
        if (recoveredMode != null && step.isSecuritySetup()) {
            twoFAMode = recoveredMode
            step = OnboardingStep.DONE
        }
    }

    val currentBackAction by rememberUpdatedState(
        step.backAction(hasReadyWallet = secretState == SecretState.READY)
    )
    val currentBackToWelcome by rememberUpdatedState(onBackToWelcome)
    val handleBack =
        remember {
            {
                when (val action = currentBackAction) {
                    is BackAction.Go -> step = action.step
                    BackAction.Consume -> Unit
                    BackAction.ExitToWelcome -> currentBackToWelcome()
                }
            }
        }

    // The NavHost remains on Tabs while this local state machine runs. Always consume
    // system Back here; editable steps use the same callback as their bottom-left button,
    // while provisioning/committed steps deliberately stay put.
    BackHandler(onBack = handleBack)

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            // Directional: forward steps slide in from the right, back-navigation from the left.
            val forward = targetState.ordinal >= initialState.ordinal
            (
                slideInHorizontally(
                    tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                ) {
                    if (forward) {
                        it / STEP_SLIDE_DIVISOR
                    } else {
                        -it / STEP_SLIDE_DIVISOR
                    }
                } +
                    fadeIn(tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing))
            ).togetherWith(
                slideOutHorizontally(
                    tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                ) {
                    if (forward) {
                        -it / STEP_SLIDE_DIVISOR
                    } else {
                        it / STEP_SLIDE_DIVISOR
                    }
                } +
                    fadeOut(tween(ZappMotion.STATE_MS)),
            )
        },
        label = "onboardingStep",
    ) { currentStep ->
        when (currentStep) {
            OnboardingStep.WALLET_INTRO -> {
                if (secretState == SecretState.READY) {
                    WalletEncryptingScreen(
                        message = stringResource(R.string.onboarding_wallet_ready_message),
                        errorMessage = null,
                        onRetry = null,
                    )
                } else {
                    WalletPhaseIntro(
                        onBack = handleBack,
                        onContinue = { step = OnboardingStep.WALLET_CHOICE },
                    )
                }
            }

            OnboardingStep.WALLET_CHOICE -> {
                if (secretState == SecretState.READY) {
                    WalletEncryptingScreen(
                        message =
                            stringResource(
                                if (restoreReturnPending) {
                                    R.string.onboarding_restoring_message
                                } else {
                                    R.string.onboarding_wallet_ready_message
                                }
                            ),
                        errorMessage = null,
                        onRetry = null,
                    )
                } else {
                    WalletChoiceScreen(
                        onBack = handleBack,
                        onCreate = {
                            restoreReturnPending = false
                            step = OnboardingStep.WALLET_CREATE
                        },
                        onRestore = {
                            restoreReturnPending = true
                            navigationRouter.forward(RestoreSeedArgs)
                        },
                    )
                }
            }

            OnboardingStep.WALLET_CREATE -> {
                // Create once, and only when there is genuinely no wallet yet. Keying the effect
                // on secretState (not Unit) is what makes it safe: process-death re-entry with a
                // wallet already persisted resolves to READY, so we skip creation and the
                // wallet-ready effect above advances to WALLET_SEED; the initial LOADING simply
                // waits for prefs to resolve rather than racing ahead and overwriting the seed.
                LaunchedEffect(secretState) {
                    if (secretState == SecretState.NONE) {
                        walletViewModel.createNewWallet()
                    }
                }
                val errorMessage =
                    if (walletProvisioningError != null) {
                        stringResource(R.string.onboarding_error_wallet_creation_failed)
                    } else {
                        null
                    }
                WalletEncryptingScreen(
                    message = stringResource(R.string.onboarding_encrypting_message),
                    errorMessage = errorMessage,
                    onRetry = { walletViewModel.createNewWallet() },
                )
            }

            OnboardingStep.WALLET_SEED -> {
                val words = walletSeed
                val errorMessage =
                    if (walletProvisioningError != null) {
                        stringResource(R.string.onboarding_error_wallet_creation_failed)
                    } else {
                        null
                    }
                when {
                    errorMessage != null -> {
                        WalletEncryptingScreen(
                            message = stringResource(R.string.onboarding_encrypting_message),
                            errorMessage = errorMessage,
                            onRetry = null,
                        )
                    }

                    words == null -> {
                        WalletEncryptingScreen(
                            message = stringResource(R.string.onboarding_encrypting_message),
                            errorMessage = null,
                            onRetry = null,
                        )
                    }

                    else -> {
                        WalletSeedPhraseScreen(
                            words = words,
                            onContinue = { step = OnboardingStep.MSG_INTRO },
                        )
                    }
                }
            }

            OnboardingStep.MSG_INTRO -> {
                // No back: the wallet is already committed at this point.
                MessagingPhaseIntro(
                    onBack = {},
                    onContinue = { step = OnboardingStep.MSG_USERNAME },
                    showBack = false,
                )
            }

            OnboardingStep.MSG_USERNAME -> {
                UsernameEntryScreen(
                    onBack = handleBack,
                    onContinue = { name ->
                        pendingUsername = name
                        step = OnboardingStep.DERIVING
                    },
                )
            }

            OnboardingStep.DERIVING -> {
                DerivingIdentityScreen(chatBootstrap = chatBootstrap)
            }

            OnboardingStep.SECURE_CHOICE -> {
                TwoFAChoiceScreen(
                    onPick = { mode ->
                        twoFAMode = mode
                        step =
                            when (mode) {
                                TwoFAMode.Bio -> OnboardingStep.BIO_SCAN
                                TwoFAMode.Pin -> OnboardingStep.PIN_SETUP
                            }
                    },
                )
            }

            OnboardingStep.BIO_SCAN -> {
                BioScanScreen(
                    isEnrolling = bioState is OnboardingSecurityVM.BioState.Prompting,
                    errorMessage = (bioState as? OnboardingSecurityVM.BioState.Error)?.message,
                    onEnroll = { securityVM.triggerBiometricSetup() },
                    onCancel = handleBack,
                    onExit = securityVM::resetBioError,
                )
            }

            OnboardingStep.PIN_SETUP -> {
                PinSetupScreen(
                    onBack = handleBack,
                    onPinConfirmed = { pin -> securityVM.savePin(pin) },
                )
            }

            OnboardingStep.DONE -> {
                OnboardingDoneScreen(
                    mode = twoFAMode,
                    onEnter = onComplete,
                )
            }
        }
    }
}

private const val STEP_SLIDE_DIVISOR = 5
