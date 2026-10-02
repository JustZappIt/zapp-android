package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.model.near.ErrorDto
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias GetNearSupportedTokensResponse = List<NearTokenDto>

interface NearApiProvider {
    @Throws(ResponseException::class, ResponseWithNearErrorException::class)
    suspend fun getSupportedTokens(): GetNearSupportedTokensResponse

    @Throws(ResponseException::class, ResponseWithNearErrorException::class)
    suspend fun requestQuote(request: QuoteRequest): QuoteResponseDto

    @Throws(ResponseException::class, ResponseWithNearErrorException::class)
    suspend fun submitDepositTransaction(request: SubmitDepositTransactionRequest)

    @Throws(ResponseException::class, ResponseWithNearErrorException::class)
    suspend fun checkSwapStatus(depositAddress: String): SwapStatusResponseDto
}

class ResponseWithNearErrorException(
    val error: ErrorDto,
    override val cause: ResponseException
) : ResponseException(
        response = cause.response,
        cachedResponseText = "Code: ${cause.response.status}, message: ${error.message}"
    )

class KtorNearApiProvider(
    private val httpClientProvider: HttpClientProvider
) : NearApiProvider {
    override suspend fun getSupportedTokens(): GetNearSupportedTokensResponse =
        execute {
            get("https://1click.chaindefuser.com/v0/tokens").body()
        }

    override suspend fun requestQuote(request: QuoteRequest): QuoteResponseDto =
        execute {
            post("https://1click.chaindefuser.com/v0/quote") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, NEAR_PARTNER_AUTHORIZATION)
                setBody(request)
            }.body()
        }

    override suspend fun submitDepositTransaction(request: SubmitDepositTransactionRequest) {
        execute {
            post("https://1click.chaindefuser.com/v0/deposit/submit") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, NEAR_PARTNER_AUTHORIZATION)
                setBody(request)
            }
        }
    }

    override suspend fun checkSwapStatus(depositAddress: String): SwapStatusResponseDto =
        execute {
            get("https://1click.chaindefuser.com/v0/status") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, NEAR_PARTNER_AUTHORIZATION)
                parameter("depositAddress", depositAddress)
            }.body()
        }

    @Suppress("TooGenericExceptionCaught")
    @Throws(ResponseException::class)
    private suspend inline fun <T> execute(
        crossinline block: suspend HttpClient.() -> T
    ): T =
        withContext(Dispatchers.IO) {
            httpClientProvider.create().use {
                try {
                    block(it)
                } catch (e: ResponseException) {
                    val response = e.response
                    val error: ErrorDto? = runCatching { response.body<ErrorDto?>() }.getOrNull()
                    if (error != null) {
                        throw ResponseWithNearErrorException(error = error, cause = e)
                    } else {
                        throw e
                    }
                }
            }
        }
}

/**
 * The 1Click partner JWT (partner_id "zapp"), as an Authorization header value, or null when this build has
 * none. Set ZAPP_NEAR_PARTNER_JWT in local.properties (or ORG_GRADLE_PROJECT_ZAPP_NEAR_PARTNER_JWT); it is
 * never committed. Without it the requests go out with no partner header, so 1Click doesn't attribute them
 * to Zapp; release builds must set it.
 */
internal val NEAR_PARTNER_AUTHORIZATION: String? =
    BuildConfig.NEAR_PARTNER_JWT.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
