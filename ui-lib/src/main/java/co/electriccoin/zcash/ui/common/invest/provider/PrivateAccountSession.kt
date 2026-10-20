package co.electriccoin.zcash.ui.common.invest.provider

import co.electriccoin.zcash.ui.common.invest.model.AuthenticateRequest
import co.electriccoin.zcash.ui.common.invest.model.BalancesResponse
import co.electriccoin.zcash.ui.common.invest.model.Erc191SignedData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.intents.IntentsLogin

/** A signed-in session with the private account. The token stays in memory; an expired one means a fresh login. */
class PrivateAccountSession(
    private val api: InvestApiProvider,
    private val salts: IntentsSaltProvider,
    private val clock: InvestServerClock,
    private val keys: PrivateAccountKeyProvider,
    /** Time since boot, so token expiry ignores wall-clock changes. */
    private val elapsedMillis: () -> Long,
) {
    private val mutex = Mutex()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var expiresAtMillis: Long = 0

    @Volatile
    private var generation = 0

    suspend fun balances(): BalancesResponse = withAccessToken { api.getBalances(it) }

    /** Forgets the session and account ID when the wallet is reset; a login in flight is then abandoned. */
    fun reset() {
        generation++
        accessToken = null
        expiresAtMillis = 0
        keys.clear()
    }

    /** Runs [block] with a valid token, once more with a fresh one if the server refuses it. */
    private suspend fun <T> withAccessToken(block: suspend (String) -> T): T {
        val token = currentToken(rejected = null)
        return try {
            block(token)
        } catch (_: InvestApiException.Unauthorized) {
            block(currentToken(rejected = token))
        }
    }

    // Logs in again only if [rejected] is still current, so calls refused together share one login.
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
                accessToken = null
                val startedIn = generation
                val (token, expiresAt) = loginWithRecovery()
                if (startedIn != generation) throw InvestApiException.SessionReset()
                accessToken = token
                expiresAtMillis = expiresAt
                token
            }
        }

    // A timestamp refusal has already corrected the clock, and a refused signature may mean a rotated salt: both
    // get one retry. A 400 may also mean a stale salt, but isn't retried.
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
        const val RENEW_MARGIN_MILLIS = 60_000L
    }
}
