package co.electriccoin.zcash.ui.screen.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Tracks the two flags that gate the post-launch experience:
 * - [isWelcomeDismissed]: true after the user has seen the [WelcomeGateView].
 * - [isOnboardingCompleted]: true after the Swiss onboarding flow finishes.
 *
 * Until both are true the user can't reach the tabs shell.
 */
class WelcomeGateVM(
    private val standardPreferenceProvider: StandardPreferenceProvider,
) : ViewModel() {
    val isWelcomeDismissed: StateFlow<Boolean?> =
        flow<Boolean?> {
            emitAll(StandardPreferenceKeys.IS_WELCOME_DISMISSED.observe(standardPreferenceProvider()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    val isOnboardingCompleted: StateFlow<Boolean?> =
        flow<Boolean?> {
            emitAll(StandardPreferenceKeys.IS_ONBOARDING_COMPLETED.observe(standardPreferenceProvider()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    fun dismissWelcome() {
        viewModelScope.launch {
            StandardPreferenceKeys.IS_WELCOME_DISMISSED.putValue(
                preferenceProvider = standardPreferenceProvider(),
                newValue = true,
            )
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            StandardPreferenceKeys.IS_ONBOARDING_COMPLETED.putValue(
                preferenceProvider = standardPreferenceProvider(),
                newValue = true,
            )
        }
    }

    /** Resets the welcome gate so it re-appears (used when backing out of onboarding). */
    fun undoDismissWelcome() {
        viewModelScope.launch {
            StandardPreferenceKeys.IS_WELCOME_DISMISSED.putValue(
                preferenceProvider = standardPreferenceProvider(),
                newValue = false,
            )
        }
    }
}
