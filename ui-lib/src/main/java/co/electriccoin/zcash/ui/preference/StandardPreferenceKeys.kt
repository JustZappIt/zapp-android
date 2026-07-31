package co.electriccoin.zcash.ui.preference

import co.electriccoin.zcash.preference.model.entry.BooleanPreferenceDefault
import co.electriccoin.zcash.preference.model.entry.IntegerPreferenceDefault
import co.electriccoin.zcash.preference.model.entry.LongPreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.preference.model.entry.StringPreferenceDefault
import co.electriccoin.zcash.ui.common.model.OnboardingState

object StandardPreferenceKeys {
    /**
     * State defining whether the user has completed any of the onboarding wallet states.
     */
    val ONBOARDING_STATE =
        IntegerPreferenceDefault(
            PreferenceKey("onboarding_state"),
            OnboardingState.NONE.toNumber()
        )

    val IS_BACKGROUND_SYNC_ENABLED = BooleanPreferenceDefault(PreferenceKey("is_background_sync_enabled"), true)

    /**
     * True once the first-launch welcome gate has been dismissed (the user tapped
     * "Get started" or "I already use Zapp"). Drives whether `WelcomeGateView` or
     * the tabs scaffold is shown.
     */
    val IS_WELCOME_DISMISSED =
        BooleanPreferenceDefault(
            PreferenceKey("is_welcome_dismissed"),
            false
        )

    /**
     * True once the user has finished the Swiss-design onboarding flow (Phase
     * intro → username → seed phrases → 2FA → Done). Independent of the Zashi
     * `ONBOARDING_STATE` so that "skip wallet" users can complete onboarding
     * without ever creating a wallet.
     */
    val IS_ONBOARDING_COMPLETED =
        BooleanPreferenceDefault(
            PreferenceKey("is_onboarding_completed"),
            false
        )

    /**
     * Screens or flows protected by required authentication
     */
    val IS_APP_ACCESS_AUTHENTICATION =
        BooleanPreferenceDefault(
            PreferenceKey("IS_APP_ACCESS_AUTHENTICATION"),
            true
        )

    val IS_HIDE_BALANCES =
        BooleanPreferenceDefault(
            PreferenceKey("IS_HIDE_BALANCES"),
            false
        )

    /**
     * Wall-clock timestamp captured in [android.app.Activity.onStop]. Used as a coarse
     * fallback for the inactivity re-auth gate; the authoritative timer is
     * [LATEST_APP_BACKGROUND_REALTIME_MILLIS], which is monotonic and resists clock
     * manipulation. Default `0L` so a fresh install reads as "ages ago" and the
     * combine-driven state machine routes the user to auth on first launch.
     */
    val LATEST_APP_BACKGROUND_TIME_MILLIS =
        LongPreferenceDefault(
            PreferenceKey("LATEST_APP_BACKGROUND_TIME_MILLIS"),
            0L
        )

    /**
     * Monotonic [android.os.SystemClock.elapsedRealtime] captured at background time.
     * Resists wall-clock manipulation. Resets to `0` on reboot, so the per-boot
     * comparison (`now < stored`) is treated as expired (i.e. a reboot forces re-auth),
     * which is what we want.
     */
    val LATEST_APP_BACKGROUND_REALTIME_MILLIS =
        LongPreferenceDefault(
            PreferenceKey("latest_app_background_realtime_millis"),
            0L
        )

    /**
     * Number of consecutive failed PIN entries since the last success or lockout reset.
     * Persisted so a process kill between attempts doesn't reset the counter.
     */
    val FAILED_PIN_ATTEMPTS_COUNT =
        IntegerPreferenceDefault(
            PreferenceKey("failed_pin_attempts_count"),
            0
        )

    /**
     * Wall-clock timestamp at which the current PIN lockout ends. `0` means no
     * lockout is in effect. Wall-clock is intentional here — bypassing it would
     * require Settings access (i.e. an already-unlocked device), which is outside
     * the threat model this gate addresses.
     */
    val PIN_LOCKOUT_END_WALLTIME_MS =
        LongPreferenceDefault(
            PreferenceKey("pin_lockout_end_walltime_ms"),
            0L
        )

    /**
     * Which 2FA method the user chose during onboarding.
     * Values: "biometric", "pin", "none" (skipped or not yet set).
     */
    val AUTH_METHOD =
        StringPreferenceDefault(
            PreferenceKey("auth_method"),
            AuthMethod.NONE.persistedValue
        )

    /**
     * True once the user has accepted the chat/messaging Terms of Service and
     * Community Guidelines. Required before first message send (Google Play UGC policy).
     */
    val IS_CHAT_TOS_ACCEPTED =
        BooleanPreferenceDefault(
            PreferenceKey("is_chat_tos_accepted"),
            false
        )

    /**
     * True when local chat message notifications are enabled. Default on; toggled
     * from Chat Settings and read by the conversations repository before posting.
     */
    val IS_CHAT_NOTIFICATIONS_ENABLED =
        BooleanPreferenceDefault(
            PreferenceKey("is_chat_notifications_enabled"),
            false
        )

    /**
     * True when distribution-specific background chat alerts are enabled.
     * Store/internal reconcile opaque FCM topics; FOSS starts the explicitly
     * opt-in persistent ntfy listener. Kept as one stable key so existing
     * opt-in state migrates deterministically across app upgrades.
     */
    val IS_CHAT_BACKGROUND_PUSH_ENABLED =
        BooleanPreferenceDefault(
            PreferenceKey("is_chat_background_push_enabled"),
            false
        )

    /**
     * True when read receipts are on. Symmetric: when off, we neither send our
     * own read acks nor display other people's. Default on; toggled from Chat
     * Settings and pushed to the worklet by the conversations repository.
     */
    val IS_CHAT_READ_RECEIPTS_ENABLED =
        BooleanPreferenceDefault(
            PreferenceKey("is_chat_read_receipts_enabled"),
            true
        )

    /**
     * True when we advertise our online/connected status to peers. Symmetric: when
     * off, peers stop seeing us online and we stop seeing them. Default on; toggled
     * from Chat Settings / the Settings tab and pushed to the worklet by the
     * conversations repository.
     */
    val IS_CHAT_SHOW_ONLINE_STATUS =
        BooleanPreferenceDefault(
            PreferenceKey("is_chat_show_online_status"),
            true
        )
}
