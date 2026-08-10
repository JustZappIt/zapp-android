package co.electriccoin.zcash.ui.common.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.screen.ironwood.IronwoodActivation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class WalletViewModel(
    application: Application,
    synchronizerProvider: SynchronizerProvider,
    persistableWalletProvider: PersistableWalletProvider,
    private val walletRepository: WalletRepository,
) : AndroidViewModel(application) {
    val synchronizer = synchronizerProvider.synchronizer

    val secretState: StateFlow<SecretState> = walletRepository.secretState

    val walletProvisioningError: StateFlow<Throwable?> = walletRepository.walletProvisioningError

    /**
     * The 24 words that back the *current* wallet, or null when no wallet exists.
     * Read by the post-create seed-reveal screen so the user sees their actual
     * recovery phrase rather than a placeholder.
     */
    val currentSeedWords: StateFlow<List<String>?> =
        persistableWalletProvider.persistableWallet
            .map { it?.seedPhrase?.split }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    /**
     * Emits `true` once — on the first launch that satisfies all Ironwood-announcement conditions:
     * the wallet has synced past the Ironwood activation height, holds a non-zero spendable Orchard
     * balance, and the one-time announcement has not been shown yet. Stays `false` while syncing or
     * while the balance is unknown.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val shouldShowIronwoodAnnouncement: StateFlow<Boolean> =
        synchronizer
            .flatMapLatest { synchronizer ->
                if (synchronizer == null) {
                    flowOf(false)
                } else {
                    combine(
                        synchronizer.fullyScannedHeight,
                        synchronizerProvider.walletBalances,
                        walletRepository.isIronwoodAnnouncementShown,
                    ) { scannedHeight, balances, isShown ->
                        val activationHeight = IronwoodActivation.heightFor(synchronizer.network)
                        // `isShown` is null when never set and true once dismissed — show while it is not true.
                        isShown != true &&
                            scannedHeight != null &&
                            scannedHeight >= activationHeight &&
                            balances != null &&
                            balances.values.any { it.orchard.available.value > 0L }
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = false
            )

    fun createNewWallet() {
        walletRepository.createNewWallet()
    }
}

/**
 * Represents the state of the wallet secret.
 */
enum class SecretState {
    LOADING,
    NONE,
    READY
}

/**
 * This constant sets the default limitation on the length of the stack trace in the [co.electriccoin.zcash.ui.common.model.SynchronizerError]
 */
const val STACKTRACE_LIMIT = 250

// TODO [#529]: Localize Synchronizer Errors
// TODO [#529]: https://github.com/Electric-Coin-Company/zashi-android/issues/529
