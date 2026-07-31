package co.electriccoin.zcash.ui.screen.onboarding

import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.screen.onboarding.view.TwoFAMode

internal enum class OnboardingStep {
    WALLET_INTRO,
    WALLET_CHOICE,
    WALLET_CREATE,
    WALLET_SEED,
    MSG_INTRO,
    MSG_USERNAME,
    DERIVING,
    SECURE_CHOICE,
    BIO_SCAN,
    PIN_SETUP,
    DONE,
}

internal enum class RestoreStep {
    SEED_ENTRY,
    BIRTHDAY,
    RESTORING,
    SEED_CONFIRM,
    USERNAME,
    DERIVING,
    SECURE_CHOICE,
    BIO_SCAN,
    PIN_SETUP,
    KEEP_OPEN,
}

internal sealed interface BackAction<out Step> {
    data class Go<Step>(
        val step: Step
    ) : BackAction<Step>

    data object Consume : BackAction<Nothing>

    data object ExitToWelcome : BackAction<Nothing>
}

internal fun OnboardingStep.backAction(hasReadyWallet: Boolean): BackAction<OnboardingStep> =
    when (this) {
        OnboardingStep.WALLET_INTRO -> {
            if (hasReadyWallet) BackAction.Consume else BackAction.ExitToWelcome
        }

        OnboardingStep.WALLET_CHOICE -> {
            if (hasReadyWallet) BackAction.Consume else BackAction.Go(OnboardingStep.WALLET_INTRO)
        }

        OnboardingStep.MSG_USERNAME -> {
            BackAction.Go(OnboardingStep.MSG_INTRO)
        }

        OnboardingStep.BIO_SCAN,
        OnboardingStep.PIN_SETUP,
        -> {
            BackAction.Go(OnboardingStep.SECURE_CHOICE)
        }

        OnboardingStep.WALLET_CREATE,
        OnboardingStep.WALLET_SEED,
        OnboardingStep.MSG_INTRO,
        OnboardingStep.DERIVING,
        OnboardingStep.SECURE_CHOICE,
        OnboardingStep.DONE,
        -> {
            BackAction.Consume
        }
    }

/**
 * Resolves a wallet that became ready while onboarding was parked on a wallet step.
 * Lost/unknown provenance takes the safe path through seed backup; only an explicit
 * restore return may skip the newly-created-wallet seed screen.
 */
internal fun OnboardingStep.walletReadyTarget(isRestoreReturn: Boolean): OnboardingStep? =
    when (this) {
        OnboardingStep.WALLET_INTRO,
        OnboardingStep.WALLET_CREATE,
        -> {
            OnboardingStep.WALLET_SEED
        }

        OnboardingStep.WALLET_CHOICE -> {
            if (isRestoreReturn) OnboardingStep.MSG_INTRO else OnboardingStep.WALLET_SEED
        }

        else -> {
            null
        }
    }

internal fun RestoreStep.backAction(hasReadyWallet: Boolean): BackAction<RestoreStep> =
    when (this) {
        RestoreStep.SEED_ENTRY -> {
            if (hasReadyWallet) BackAction.Consume else BackAction.ExitToWelcome
        }

        RestoreStep.BIRTHDAY -> {
            BackAction.Go(RestoreStep.SEED_ENTRY)
        }

        RestoreStep.USERNAME -> {
            BackAction.Go(RestoreStep.SEED_CONFIRM)
        }

        RestoreStep.BIO_SCAN,
        RestoreStep.PIN_SETUP,
        -> {
            BackAction.Go(RestoreStep.SECURE_CHOICE)
        }

        RestoreStep.RESTORING,
        RestoreStep.SEED_CONFIRM,
        RestoreStep.DERIVING,
        RestoreStep.SECURE_CHOICE,
        RestoreStep.KEEP_OPEN,
        -> {
            BackAction.Consume
        }
    }

internal fun OnboardingStep.isMessagingGate(): Boolean =
    this == OnboardingStep.MSG_INTRO ||
        this == OnboardingStep.MSG_USERNAME ||
        this == OnboardingStep.DERIVING

internal fun OnboardingStep.isSecuritySetup(): Boolean =
    this == OnboardingStep.SECURE_CHOICE ||
        this == OnboardingStep.BIO_SCAN ||
        this == OnboardingStep.PIN_SETUP

internal fun RestoreStep.isIdentityGate(): Boolean = this == RestoreStep.USERNAME || this == RestoreStep.DERIVING

internal fun RestoreStep.isSecuritySetup(): Boolean =
    this == RestoreStep.SECURE_CHOICE ||
        this == RestoreStep.BIO_SCAN ||
        this == RestoreStep.PIN_SETUP

internal fun AuthMethod.toTwoFAMode(): TwoFAMode? =
    when (this) {
        AuthMethod.NONE -> null
        AuthMethod.BIOMETRIC -> TwoFAMode.Bio
        AuthMethod.PIN -> TwoFAMode.Pin
    }

/**
 * Repairs a restored saveable step whose non-saveable secret/restore job disappeared
 * with the process. Seed words are deliberately not stored in a Bundle.
 */
internal fun RestoreStep.recoveryTarget(
    secretState: SecretState,
    hasValidSeed: Boolean,
    isRestoring: Boolean,
    hasProvisioningError: Boolean,
    hasRestoreError: Boolean,
): RestoreStep? =
    when {
        secretState == SecretState.LOADING -> null

        this == RestoreStep.SEED_ENTRY && secretState == SecretState.READY -> RestoreStep.SEED_CONFIRM

        this == RestoreStep.BIRTHDAY && !hasValidSeed -> RestoreStep.SEED_ENTRY

        this == RestoreStep.RESTORING &&
            secretState == SecretState.NONE &&
            !isRestoring &&
            !hasProvisioningError &&
            !hasRestoreError -> RestoreStep.SEED_ENTRY

        secretState == SecretState.NONE && isCommittedRestoreStep() -> RestoreStep.SEED_ENTRY

        else -> null
    }

private fun RestoreStep.isCommittedRestoreStep(): Boolean =
    when (this) {
        RestoreStep.SEED_CONFIRM,
        RestoreStep.USERNAME,
        RestoreStep.DERIVING,
        RestoreStep.SECURE_CHOICE,
        RestoreStep.BIO_SCAN,
        RestoreStep.PIN_SETUP,
        RestoreStep.KEEP_OPEN,
        -> true

        RestoreStep.SEED_ENTRY,
        RestoreStep.BIRTHDAY,
        RestoreStep.RESTORING,
        -> false
    }
