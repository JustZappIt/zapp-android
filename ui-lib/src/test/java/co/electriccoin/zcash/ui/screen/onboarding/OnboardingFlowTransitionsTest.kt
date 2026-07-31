package co.electriccoin.zcash.ui.screen.onboarding

import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.screen.onboarding.view.TwoFAMode
import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingFlowTransitionsTest {
    @Test
    fun `persisted auth method restores exact completion mode`() {
        assertEquals(null, AuthMethod.NONE.toTwoFAMode())
        assertEquals(TwoFAMode.Bio, AuthMethod.BIOMETRIC.toTwoFAMode())
        assertEquals(TwoFAMode.Pin, AuthMethod.PIN.toTwoFAMode())
    }

    @Test
    fun `restore return skips create-only seed backup`() {
        assertEquals(
            OnboardingStep.MSG_INTRO,
            OnboardingStep.WALLET_CHOICE.walletReadyTarget(isRestoreReturn = true),
        )
    }

    @Test
    fun `unknown ready wallet takes safe seed backup path`() {
        assertEquals(
            OnboardingStep.WALLET_SEED,
            OnboardingStep.WALLET_INTRO.walletReadyTarget(isRestoreReturn = false),
        )
        assertEquals(
            OnboardingStep.WALLET_SEED,
            OnboardingStep.WALLET_CHOICE.walletReadyTarget(isRestoreReturn = false),
        )
    }

    @Test
    fun `committed onboarding steps consume back`() {
        assertEquals(BackAction.Consume, OnboardingStep.WALLET_CREATE.backAction(hasReadyWallet = false))
        assertEquals(BackAction.Consume, OnboardingStep.WALLET_SEED.backAction(hasReadyWallet = true))
        assertEquals(BackAction.Consume, OnboardingStep.MSG_INTRO.backAction(hasReadyWallet = true))
        assertEquals(BackAction.Consume, OnboardingStep.DERIVING.backAction(hasReadyWallet = true))
        assertEquals(BackAction.Consume, OnboardingStep.SECURE_CHOICE.backAction(hasReadyWallet = true))
        assertEquals(BackAction.Consume, OnboardingStep.DONE.backAction(hasReadyWallet = true))
    }

    @Test
    fun `editable onboarding steps move to stable predecessors`() {
        assertEquals(
            BackAction.Go(OnboardingStep.WALLET_INTRO),
            OnboardingStep.WALLET_CHOICE.backAction(hasReadyWallet = false),
        )
        assertEquals(
            BackAction.Go(OnboardingStep.MSG_INTRO),
            OnboardingStep.MSG_USERNAME.backAction(hasReadyWallet = true),
        )
        assertEquals(
            BackAction.Go(OnboardingStep.SECURE_CHOICE),
            OnboardingStep.BIO_SCAN.backAction(hasReadyWallet = true),
        )
        assertEquals(
            BackAction.Go(OnboardingStep.SECURE_CHOICE),
            OnboardingStep.PIN_SETUP.backAction(hasReadyWallet = true),
        )
    }

    @Test
    fun `wallet intro exits only before wallet commitment`() {
        assertEquals(
            BackAction.ExitToWelcome,
            OnboardingStep.WALLET_INTRO.backAction(hasReadyWallet = false),
        )
        assertEquals(
            BackAction.Consume,
            OnboardingStep.WALLET_INTRO.backAction(hasReadyWallet = true),
        )
    }

    @Test
    fun `restore committed and provisioning steps consume back`() {
        assertEquals(BackAction.Consume, RestoreStep.RESTORING.backAction(hasReadyWallet = true))
        assertEquals(BackAction.Consume, RestoreStep.SEED_CONFIRM.backAction(hasReadyWallet = true))
        assertEquals(BackAction.Consume, RestoreStep.DERIVING.backAction(hasReadyWallet = true))
        assertEquals(BackAction.Consume, RestoreStep.SECURE_CHOICE.backAction(hasReadyWallet = true))
        assertEquals(BackAction.Consume, RestoreStep.KEEP_OPEN.backAction(hasReadyWallet = true))
    }

    @Test
    fun `restore editable steps move to stable predecessors`() {
        assertEquals(BackAction.Go(RestoreStep.SEED_ENTRY), RestoreStep.BIRTHDAY.backAction(hasReadyWallet = false))
        assertEquals(BackAction.Go(RestoreStep.SEED_CONFIRM), RestoreStep.USERNAME.backAction(hasReadyWallet = true))
        assertEquals(BackAction.Go(RestoreStep.SECURE_CHOICE), RestoreStep.BIO_SCAN.backAction(hasReadyWallet = true))
        assertEquals(BackAction.Go(RestoreStep.SECURE_CHOICE), RestoreStep.PIN_SETUP.backAction(hasReadyWallet = true))
    }

    @Test
    fun `restore seed entry exits only before wallet commitment`() {
        assertEquals(BackAction.ExitToWelcome, RestoreStep.SEED_ENTRY.backAction(hasReadyWallet = false))
        assertEquals(BackAction.Consume, RestoreStep.SEED_ENTRY.backAction(hasReadyWallet = true))
    }

    @Test
    fun `process recovery restarts when non-saveable restore prerequisites are gone`() {
        assertEquals(
            RestoreStep.SEED_ENTRY,
            RestoreStep.BIRTHDAY.recoveryTarget(
                secretState = SecretState.NONE,
                hasValidSeed = false,
                isRestoring = false,
                hasProvisioningError = false,
                hasRestoreError = false,
            ),
        )
        assertEquals(
            RestoreStep.SEED_ENTRY,
            RestoreStep.RESTORING.recoveryTarget(
                secretState = SecretState.NONE,
                hasValidSeed = false,
                isRestoring = false,
                hasProvisioningError = false,
                hasRestoreError = false,
            ),
        )
        assertEquals(
            RestoreStep.SEED_ENTRY,
            RestoreStep.USERNAME.recoveryTarget(
                secretState = SecretState.NONE,
                hasValidSeed = false,
                isRestoring = false,
                hasProvisioningError = false,
                hasRestoreError = false,
            ),
        )
    }

    @Test
    fun `active or completed restore is not reset`() {
        assertEquals(
            null,
            RestoreStep.RESTORING.recoveryTarget(
                secretState = SecretState.NONE,
                hasValidSeed = true,
                isRestoring = true,
                hasProvisioningError = false,
                hasRestoreError = false,
            )
        )
        assertEquals(
            null,
            RestoreStep.SEED_CONFIRM.recoveryTarget(
                secretState = SecretState.READY,
                hasValidSeed = false,
                isRestoring = false,
                hasProvisioningError = false,
                hasRestoreError = false,
            )
        )
    }

    @Test
    fun `restore failure remains on retry screen even when no job is active`() {
        assertEquals(
            null,
            RestoreStep.RESTORING.recoveryTarget(
                secretState = SecretState.NONE,
                hasValidSeed = true,
                isRestoring = false,
                hasProvisioningError = true,
                hasRestoreError = false,
            ),
        )
        assertEquals(
            null,
            RestoreStep.RESTORING.recoveryTarget(
                secretState = SecretState.NONE,
                hasValidSeed = true,
                isRestoring = false,
                hasProvisioningError = false,
                hasRestoreError = true,
            ),
        )
    }

    @Test
    fun `ready wallet at restore entry resumes seed confirmation`() {
        assertEquals(
            RestoreStep.SEED_CONFIRM,
            RestoreStep.SEED_ENTRY.recoveryTarget(
                secretState = SecretState.READY,
                hasValidSeed = false,
                isRestoring = false,
                hasProvisioningError = false,
                hasRestoreError = false,
            ),
        )
    }
}
