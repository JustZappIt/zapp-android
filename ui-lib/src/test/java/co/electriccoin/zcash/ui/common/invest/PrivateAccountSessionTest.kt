package co.electriccoin.zcash.ui.common.invest

import co.electriccoin.zcash.ui.common.invest.model.AuthenticateRequest
import co.electriccoin.zcash.ui.common.invest.model.AuthenticateResponse
import co.electriccoin.zcash.ui.common.invest.model.BalancesResponse
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentResponse
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentResponse
import co.electriccoin.zcash.ui.common.invest.provider.IntentsSaltProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestServerClock
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.offramp.account.SeedPhraseSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrivateAccountSessionTest {
    private var wallNow = 1_790_604_000_000L // 2026-09-28T14:00:00Z
    private var monotonicNow = 0L
    private val handedOut = mutableListOf<CharArray>()
    private val seed = SeedPhraseSource { TEST_MNEMONIC.toCharArray().also { handedOut += it } }
    private val keys = PrivateAccountKeyProvider(seed)
    private val clock = InvestServerClock(deviceNowMillis = { wallNow })
    private val saltHttp = FakeHttpClientProvider { jsonResponse(SALT_RPC_RESULT) }
    private val salts = IntentsSaltProvider(saltHttp, clock)

    private fun session(api: InvestApiProvider) =
        PrivateAccountSession(api, salts, clock, keys, elapsedMillis = { monotonicNow })

    @Test
    fun `signs in once with a login the private-account key signed, then reuses the token`() =
        runTest {
            val api = FakeApi()
            val session = session(api)

            session.balances()
            session.balances()

            assertEquals(1, api.logins.size)
            assertEquals(listOf("token-1", "token-1"), api.balanceTokens)
            val login = api.logins.single().signedData
            assertEquals("erc191", login.standard)
            val payload = Json.parseToJsonElement(login.payload).jsonObject
            assertEquals(TEST_ACCOUNT_ID, payload.getValue("signer_id").jsonPrimitive.content)
            assertEquals("intents.near", payload.getValue("verifying_contract").jsonPrimitive.content)
            assertEquals("2026-09-28T14:03:00.000Z", payload.getValue("deadline").jsonPrimitive.content)
            assertTrue(login.signature.startsWith("secp256k1:"))
        }

    @Test
    fun `signs in again a minute before the token expires, on the monotonic clock`() =
        runTest {
            val api = FakeApi()
            val session = session(api)

            session.balances()
            wallNow += 3_600_000 // the user moves the wall clock an hour: the token is still good
            monotonicNow += 839_999 // 900 s token, renewed 60 s early
            session.balances()
            monotonicNow += 1
            session.balances()

            assertEquals(listOf("token-1", "token-1", "token-2"), api.balanceTokens)
        }

    @Test
    fun `a refused token gets exactly one fresh login`() =
        runTest {
            val api = FakeApi(rejectTokens = setOf("token-1"))

            session(api).balances()

            assertEquals(listOf("token-1", "token-2"), api.balanceTokens)

            val alwaysRefused = FakeApi(rejectTokens = setOf("token-1", "token-2"))
            assertFailsWith<InvestApiException.Unauthorized> { session(alwaysRefused).balances() }
            assertEquals(2, alwaysRefused.logins.size)
        }

    @Test
    fun `calls refused at the same time share one fresh login`() =
        runTest {
            val bothFailed = CompletableDeferred<Unit>()
            var refusals = 0
            val api =
                FakeApi(
                    onBalances = { token ->
                        if (token == "token-1") {
                            refusals++
                            if (refusals == 2) bothFailed.complete(Unit) else bothFailed.await()
                            throw InvestApiException.Unauthorized(null)
                        }
                    },
                )
            val session = session(api)
            session.balances().also { api.balanceTokens.clear() }
            api.onBalancesEnabled = true

            listOf(async { session.balances() }, async { session.balances() }).awaitAll()

            assertEquals(2, api.logins.size) // the first login, then one shared re-login
            assertEquals(listOf("token-1", "token-1", "token-2", "token-2"), api.balanceTokens)
        }

    @Test
    fun `a timestamp refusal is retried once with the clock its response corrected`() =
        runTest {
            val api = FakeApi(loginFailures = mutableListOf(InvestApiException.ClockSkew("cid")))

            session(api).balances()

            assertEquals(2, api.loginAttempts)
            assertEquals(1, api.logins.size)
        }

    @Test
    fun `a refused login fetches the salt again and retries once`() =
        runTest {
            val refused = InvestApiException.LoginRefused("signature verification failed", null)
            val api = FakeApi(loginFailures = mutableListOf(refused))

            session(api).balances()

            assertEquals(2, api.loginAttempts)
            assertEquals(2, saltHttp.requests.size)
        }

    @Test
    fun `a second refusal reaches the caller`() =
        runTest {
            val api =
                FakeApi(
                    loginFailures =
                        mutableListOf(
                            InvestApiException.LoginRefused("signature verification failed", null),
                            InvestApiException.LoginRefused("signature verification failed", null),
                        ),
                )

            assertFailsWith<InvestApiException.LoginRefused> { session(api).balances() }
            assertEquals(2, api.loginAttempts)
        }

    @Test
    fun `a 400 during login drops the salt but is not retried`() =
        runTest {
            val api = FakeApi(loginFailures = mutableListOf(InvestApiException.Api(400, "Invalid signed data", null)))

            assertFailsWith<InvestApiException.Api> { session(api).balances() }
            assertEquals(1, api.loginAttempts)

            session(FakeApi()).balances()
            assertEquals(2, saltHttp.requests.size) // fetched again after the 400
        }

    @Test
    fun `a login that lands after reset does not store its token`() =
        runTest {
            val loginStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val api = FakeApi(beforeLogin = { if (it == 1) loginStarted.complete(Unit).also { release.await() } })
            val session = session(api)

            val first = async { session.balances() }
            loginStarted.await()
            session.reset() // doesn't wait for the login in flight
            release.complete(Unit)
            first.await()
            session.balances()

            assertEquals(listOf("token-1", "token-2"), api.balanceTokens)
        }

    @Test
    fun `reset forgets the token and the account id`() =
        runTest {
            val api = FakeApi()
            val session = session(api)
            session.balances()
            keys.accountId()

            session.reset()
            session.balances()

            assertEquals(2, api.logins.size)
        }

    @Test
    fun `the recovery phrase buffer is wiped after every login`() =
        runTest {
            session(FakeApi()).balances()

            assertTrue(handedOut.isNotEmpty())
            handedOut.forEach { buffer -> assertTrue(buffer.all { it == '\u0000' }) }
        }

    @Test
    fun `the account id is the m 44 60 7 0 0 address`() =
        runTest {
            assertEquals(TEST_ACCOUNT_ID, keys.accountId())
        }

    private class FakeApi(
        private val rejectTokens: Set<String> = emptySet(),
        private val loginFailures: MutableList<InvestApiException> = mutableListOf(),
        private val onBalances: suspend (String) -> Unit = {},
        private val beforeLogin: suspend (attempt: Int) -> Unit = {},
    ) : InvestApiProvider {
        val logins = mutableListOf<AuthenticateRequest>()
        val balanceTokens = mutableListOf<String>()
        var loginAttempts = 0
        var onBalancesEnabled = false

        override suspend fun authenticate(request: AuthenticateRequest): AuthenticateResponse {
            loginAttempts++
            beforeLogin(loginAttempts)
            loginFailures.removeFirstOrNull()?.let { throw it }
            logins += request
            return AuthenticateResponse(accessToken = "token-${logins.size}", expiresIn = 900)
        }

        override suspend fun getBalances(accessToken: String): BalancesResponse {
            balanceTokens += accessToken
            if (onBalancesEnabled) onBalances(accessToken)
            if (accessToken in rejectTokens) throw InvestApiException.Unauthorized(null)
            return BalancesResponse(emptyList())
        }

        override suspend fun getOndoTokens(): List<NearTokenDto> = error("unused")

        override suspend fun requestQuote(request: QuoteRequest): QuoteResponseDto = error("unused")

        override suspend fun submitDeposit(request: SubmitDepositTransactionRequest) = error("unused")

        override suspend fun checkStatus(depositAddress: String): SwapStatusResponseDto = error("unused")

        override suspend fun generateIntent(request: GenerateIntentRequest): GenerateIntentResponse = error("unused")

        override suspend fun submitIntent(request: SubmitIntentRequest): SubmitIntentResponse = error("unused")
    }
}
