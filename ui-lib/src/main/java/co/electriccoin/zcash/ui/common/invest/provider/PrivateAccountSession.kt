package co.electriccoin.zcash.ui.common.invest.provider

import co.electriccoin.zcash.ui.common.invest.model.AuthenticateRequest
import co.electriccoin.zcash.ui.common.invest.model.BalancesResponse
import co.electriccoin.zcash.ui.common.invest.model.Erc191SignedData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.intents.IntentsLogin

/**
 * A signed-in session with the user's private account. The access token lives in memory only; nothing is
 * stored, so there is no refresh token to protect (decided 2026-09-27). When the token is about to expire,
 * or the server refuses it, the key signs a fresh login.
 */
class PrivateAccountSession(
    private val api: InvestApiProvider,
    private val salts: IntentsSaltProvider,
    private val clock: InvestServerClock,
    private val keys: PrivateAccountKeyProvider,
    /**
     * Time since boot, deep sleep included (`SystemClock.elapsedRealtime` in the app), so token expiry
     * neither moves with the wall clock nor stands still while the phone sleeps.
     */
    private val elapsedMillis: () -> Long,
) {
    private val mutex = Mutex()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var expiresAtMillis: Long = 0

    // Bumped by reset(); a login that started before a reset doesn't store its token afterwards.
    @Volatile
    private var generation = 0

    suspend fun balances(): BalancesResponse = withAccessToken { api.getBalances(it) }

    /**
     * Forgets the session and the cached account ID; called when the wallet is reset. It doesn't wait for a
     * login in flight: that login's token is dropped when it lands.
     */
    fun reset() {
        generation++
        accessToken = null
        expiresAtMillis = 0
        keys.clear()
    }

    /**
     * Runs [block] with a valid token. If the server refuses it, [block] runs once more with a fresh one, so
     * it must be safe to repeat (reads only).
     */
    private suspend fun <T> withAccessToken(block: suspend (String) -> T): T {
        val token = currentToken(rejected = null)
        return try {
            block(token)
        } catch (_: InvestApiException.Unauthorized) {
            block(currentToken(rejected = token))
        }
    }

    /**
     * The token to use. [rejected] is one the server just refused: a new login happens only if it is still
     * the current token, so several calls refused at once share one login instead of each signing their own.
     */
    private suspend fun currentToken(rejected: String?): String =
        mutex.withLock {
            val current = accessToken
            val usable =
                current != null &&
                    current != rejected &&
                    elapsedMillis() < expiresAtMillis - RENEW_MARGIN_MILLIS
            if (usable) {
                checkNotNull(current)
            } else {
                // Never hand out a token known to be expired or refused, even if the login below fails.
                accessToken = null
                val startedIn = generation
                val (token, expiresAt) = loginWithRecovery()
                if (startedIn == generation) {
                    accessToken = token
                    expiresAtMillis = expiresAt
                }
                token
            }
        }

    /**
     * One login, retried once for the two refusals a second attempt can fix: a timestamp refusal (whose
     * response has already corrected [clock]) and a refused signature (which a rotated salt explains, so the
     * salt is fetched again first). A 400 may also mean a stale salt, so it drops the salt too, but isn't
     * retried: a permanent 400 would otherwise double every failed login.
     */
    private suspend fun loginWithRecovery(): Pair<String, Long> =
        try {
            login()
        } catch (_: InvestApiException.ClockSkew) {
            login()
        } catch (_: InvestApiException.LoginRefused) {
            salts.invalidate()
            login()
        } catch (e: InvestApiException.Api) {
            if (e.status == HTTP_BAD_REQUEST) salts.invalidate()
            throw e
        }

    /** The access token and when it expires, on [elapsedMillis]. */
    private suspend fun login(): Pair<String, Long> {
        val salt = salts.salt()
        val signed = keys.withKey { key -> IntentsLogin.sign(key, salt, clock.nowMillis()) }
        val response =
            api.authenticate(
                AuthenticateRequest(
                    signedData =
                        Erc191SignedData(
                            standard = signed.standard,
                            payload = signed.payload,
                            signature = signed.signature,
                        ),
                ),
            )
        return response.accessToken to elapsedMillis() + response.expiresIn * MILLIS_PER_SECOND
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val HTTP_BAD_REQUEST = 400

        // Sign in again this long before the token's stated expiry, so a request never races it.
        const val RENEW_MARGIN_MILLIS = 60_000L
    }
}
