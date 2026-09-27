package co.electriccoin.zcash.ui.common.invest.provider

import cash.z.ecc.android.sdk.exception.TorInitializationErrorException
import cash.z.ecc.android.sdk.exception.TorUnavailableException
import co.electriccoin.zcash.ui.common.invest.model.AuthenticateRequest
import co.electriccoin.zcash.ui.common.invest.model.AuthenticateResponse
import co.electriccoin.zcash.ui.common.invest.model.BalancesResponse
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.GenerateIntentResponse
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentRequest
import co.electriccoin.zcash.ui.common.invest.model.SubmitIntentResponse
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.common.provider.NEAR_PARTNER_AUTHORIZATION
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.retry
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * Every 1Click call Invest makes. It follows the user's Tor setting through [HttpClientProvider.create]:
 * over Tor when it is on, and over the direct connection when the user has turned it off and chosen to
 * go on without it (decided 2026-09-27; Invest encourages turning Tor on, since the account ID, session
 * and refund address all tie a person to the IP that sends them).
 */
interface InvestApiProvider {
    /** `/v0/tokens?ondoTokens=true`: the default list plus the Ondo assets, with prices. */
    suspend fun getOndoTokens(): List<NearTokenDto>

    suspend fun requestQuote(request: QuoteRequest): QuoteResponseDto

    suspend fun submitDeposit(request: SubmitDepositTransactionRequest)

    suspend fun checkStatus(depositAddress: String): SwapStatusResponseDto

    suspend fun generateIntent(request: GenerateIntentRequest): GenerateIntentResponse

    suspend fun submitIntent(request: SubmitIntentRequest): SubmitIntentResponse

    /** No partner JWT: the signed message is the credential. */
    suspend fun authenticate(request: AuthenticateRequest): AuthenticateResponse

    suspend fun getBalances(accessToken: String): BalancesResponse
}

// One function per 1Click endpoint the interface names, plus the shared call and error mapping.
@Suppress("TooManyFunctions")
class KtorInvestApiProvider(
    private val httpClientProvider: HttpClientProvider,
    private val serverClock: InvestServerClock,
) : InvestApiProvider {
    override suspend fun getOndoTokens(): List<NearTokenDto> =
        call {
            get("$BASE_URL/v0/tokens") { parameter("ondoTokens", "true") }
        }

    override suspend fun requestQuote(request: QuoteRequest): QuoteResponseDto =
        call {
            post("$BASE_URL/v0/quote") {
                partnerJson()
                setBody(request)
            }
        }

    override suspend fun submitDeposit(request: SubmitDepositTransactionRequest) {
        call<Unit> {
            post("$BASE_URL/v0/deposit/submit") {
                partnerJson()
                setBody(request)
            }
        }
    }

    override suspend fun checkStatus(depositAddress: String): SwapStatusResponseDto =
        call {
            get("$BASE_URL/v0/status") {
                header(HttpHeaders.Authorization, NEAR_PARTNER_AUTHORIZATION)
                parameter("depositAddress", depositAddress)
            }
        }

    override suspend fun generateIntent(request: GenerateIntentRequest): GenerateIntentResponse =
        call {
            post("$BASE_URL/v0/generate-intent") {
                partnerJson()
                retry { noRetry() }
                setBody(request)
            }
        }

    override suspend fun submitIntent(request: SubmitIntentRequest): SubmitIntentResponse =
        call {
            post("$BASE_URL/v0/submit-intent") {
                partnerJson()
                // A replayed submission is refused for its used nonce, which would read as a failure after
                // the first attempt succeeded unseen. The caller polls status instead of retrying.
                retry { noRetry() }
                setBody(request)
            }
        }

    override suspend fun authenticate(request: AuthenticateRequest): AuthenticateResponse =
        call(isLogin = true) {
            post("$BASE_URL/v0/auth/authenticate") {
                contentType(ContentType.Application.Json)
                // Each signed login carries a one-time nonce; the session signs a fresh one instead.
                retry { noRetry() }
                setBody(request)
            }
        }

    override suspend fun getBalances(accessToken: String): BalancesResponse =
        call {
            get("$BASE_URL/v0/account/balances") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.partnerJson() {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, NEAR_PARTNER_AUTHORIZATION)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun <reified T> call(
        isLogin: Boolean = false,
        crossinline request: suspend HttpClient.() -> HttpResponse,
    ): T =
        withContext(Dispatchers.IO) {
            try {
                httpClientProvider.create().use { client ->
                    val response = client.request()
                    serverClock.observe(response.headers[HttpHeaders.Date], fromOneClick = true)
                    if (T::class == Unit::class) Unit as T else response.body<T>()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResponseException) {
                serverClock.observe(e.response.headers[HttpHeaders.Date], fromOneClick = true)
                throw e.toInvestException(isLogin)
            } catch (e: TorUnavailableException) {
                throw InvestApiException.TorUnavailable(e)
            } catch (e: TorInitializationErrorException) {
                throw InvestApiException.TorUnavailable(e)
            } catch (_: SerializationException) {
                // A decoding message quotes the body it choked on, which can be a session token.
                throw InvestApiException.Unreachable(IllegalStateException("Unreadable ${T::class.simpleName}"))
            } catch (_: ContentConvertException) {
                // Ktor's wrapper for the same failure, with the same quoted body.
                throw InvestApiException.Unreachable(IllegalStateException("Unreadable ${T::class.simpleName}"))
            } catch (e: Exception) {
                throw InvestApiException.Unreachable(e)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun ResponseException.toInvestException(isLogin: Boolean): InvestApiException {
        val body =
            try {
                response.body<InvestErrorBody>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        val message = body?.message.orEmpty()
        val status = response.status
        return when {
            status == HttpStatusCode.BadRequest && message.contains(NO_LIQUIDITY, ignoreCase = true) -> {
                InvestApiException.NoPrice(body?.correlationId)
            }

            status == HttpStatusCode.Unauthorized && message.contains(TIMESTAMP_REJECTED, ignoreCase = true) -> {
                InvestApiException.ClockSkew(body?.correlationId)
            }

            status == HttpStatusCode.Unauthorized && isLogin -> {
                InvestApiException.LoginRefused(body?.message, body?.correlationId)
            }

            status == HttpStatusCode.Unauthorized -> {
                InvestApiException.Unauthorized(body?.correlationId)
            }

            else -> {
                InvestApiException.Api(status.value, body?.message, body?.correlationId)
            }
        }
    }

    internal companion object {
        const val HOST = "1click.chaindefuser.com"
        const val BASE_URL = "https://$HOST"
        private const val NO_LIQUIDITY = "No liquidity available"
        private const val TIMESTAMP_REJECTED = "timestamp validation failed"
    }
}
