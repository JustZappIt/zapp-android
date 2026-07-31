package co.electriccoin.zcash.ui.common.security

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.preference.EncryptedPreferenceKeys
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import kotlin.time.Duration.Companion.seconds

/**
 * Centralised PIN verification with global rate-limiting.
 *
 * All in-app PIN entry surfaces (app-open, send-funds, wallet backup reveal)
 * should funnel through [tryVerify] so the failed-attempt counter and the
 * 30-second lockout are shared across screens. Storing the counter and lockout
 * deadline in [StandardPreferenceProvider] means a process kill between
 * attempts doesn't reset the gate, and a single-screen brute-force attempt
 * is also blocked from the other PIN entry points until the lockout clears.
 */
object PinAuthGate {
    /**
     * Number of consecutive wrong PINs that triggers a [LOCKOUT_DURATION] window.
     * On entering lockout, the counter resets to zero so the next 5 wrong PINs
     * during a future session enter another window.
     */
    const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5

    /** Cool-down duration applied when [MAX_ATTEMPTS_BEFORE_LOCKOUT] is reached. */
    val LOCKOUT_DURATION = 30.seconds
    private val LOCKOUT_DURATION_MS = LOCKOUT_DURATION.inWholeMilliseconds

    sealed class Result {
        object Success : Result()

        object Wrong : Result()

        data class Locked(
            val msUntilUnlock: Long
        ) : Result()
    }

    /**
     * Returns the milliseconds remaining on the current lockout, or `0` if not
     * locked. Use to gate UI affordances (disable input, show countdown) without
     * actually attempting a verify.
     */
    suspend fun msUntilUnlock(standardPreferenceProvider: StandardPreferenceProvider): Long {
        val end =
            StandardPreferenceKeys.PIN_LOCKOUT_END_WALLTIME_MS
                .getValue(standardPreferenceProvider())
        return (end - System.currentTimeMillis()).coerceAtLeast(0)
    }

    /**
     * Checks the lockout, then verifies [pin]. On success: clears counter and
     * lockout. On failure: increments counter; if it reaches
     * [MAX_ATTEMPTS_BEFORE_LOCKOUT], starts a [LOCKOUT_DURATION] lockout and
     * returns [Result.Locked].
     */
    suspend fun tryVerify(
        pin: String,
        encryptedPreferenceProvider: EncryptedPreferenceProvider,
        standardPreferenceProvider: StandardPreferenceProvider,
    ): Result {
        val standard = standardPreferenceProvider()
        val now = System.currentTimeMillis()
        val lockoutEnd = StandardPreferenceKeys.PIN_LOCKOUT_END_WALLTIME_MS.getValue(standard)
        if (lockoutEnd > now) {
            return Result.Locked(lockoutEnd - now)
        }

        val matched = EncryptedPreferenceKeys.verifyAndUpgradePin(pin, encryptedPreferenceProvider)
        return if (matched) {
            StandardPreferenceKeys.FAILED_PIN_ATTEMPTS_COUNT.putValue(standard, 0)
            StandardPreferenceKeys.PIN_LOCKOUT_END_WALLTIME_MS.putValue(standard, 0L)
            Result.Success
        } else {
            val nextCount = StandardPreferenceKeys.FAILED_PIN_ATTEMPTS_COUNT.getValue(standard) + 1
            if (nextCount >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
                val end = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                StandardPreferenceKeys.PIN_LOCKOUT_END_WALLTIME_MS.putValue(standard, end)
                StandardPreferenceKeys.FAILED_PIN_ATTEMPTS_COUNT.putValue(standard, 0)
                Result.Locked(LOCKOUT_DURATION_MS)
            } else {
                StandardPreferenceKeys.FAILED_PIN_ATTEMPTS_COUNT.putValue(standard, nextCount)
                Result.Wrong
            }
        }
    }
}
