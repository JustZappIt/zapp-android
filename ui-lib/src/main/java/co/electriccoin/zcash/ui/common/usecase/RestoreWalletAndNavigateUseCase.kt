package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class RestoreWalletAndNavigateUseCase(
    private val restoreWallet: RestoreWalletUseCase,
    private val walletRepository: WalletRepository,
    private val showError: ShowErrorUseCase,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke(
        seed: String,
        blockHeight: Long,
    ) {
        restoreWallet(
            seedPhrase = SeedPhrase.new(seed.trim()),
            birthday = BlockHeight.new(blockHeight),
        )
        // WalletRepository.restoreWallet runs its work in an internal scope and returns
        // synchronously, so wait for the secret state to flip before navigating.
        val didSucceed =
            combine(
                walletRepository.secretState,
                walletRepository.walletProvisioningError,
            ) { state, error ->
                when {
                    error != null -> false
                    state == SecretState.READY -> true
                    else -> null
                }
            }.filterNotNull().first()
        if (didSucceed) {
            // Resume the existing Tabs root instead of navigating to Tabs again. Pushing a
            // second Tabs entry creates a fresh ZappOnboardingFlow and loses its saveable step,
            // which sent successful onboarding restores back to the wallet intro/choice UI.
            navigationRouter.backToRoot()
        } else {
            showError()
        }
    }
}
