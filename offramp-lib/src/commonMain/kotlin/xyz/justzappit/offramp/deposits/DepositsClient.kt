// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.serialization.KSerializer
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.AtomicSwapHttpException
import xyz.justzappit.offramp.atomicswap.AtomicSwapService
import xyz.justzappit.offramp.atomicswap.RelayerTerms
import xyz.justzappit.offramp.atomicswap.Sent
import xyz.justzappit.offramp.atomicswap.SwapService
import xyz.justzappit.offramp.atomicswap.requireWellFormed
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** A relayer's deposits: its terms, and the posts of a signed shield or rescue, which it sends and pays the gas for. */
interface DepositsRelayer {
    suspend fun terms(): RelayerTerms

    suspend fun shield(request: ShieldDeposit): DepositAnswer

    suspend fun rescue(request: RescueDeposit): DepositAnswer
}

/** What a relayer made of a deposit or a rescue posted to it, as zecSwap's `docs/deposits.md` has its answers. */
sealed interface DepositAnswer {
    /** `200`: sent in [transactions], not yet mined; the same request posted again names them again. */
    data class Sent(
        val transactions: List<TxHash>
    ) : DepositAnswer

    /**
     * `409`: the authorization is used, or another deposit of the address is on its way, in the relayer's own
     * [transactions]; none where it doesn't know one, and then the chain tells which.
     */
    data class Conflict(
        val transactions: List<TxHash>
    ) : DepositAnswer

    /** `400`, `413`, `415` or `422`: nothing was sent, and nothing will be. [reason] is the relayer's, for the logs. */
    data class Refused(
        val status: Int,
        val reason: String,
    ) : DepositAnswer

    /**
     * Anything else, no answer included: post the same bytes again later, no sooner than [after] where the relayer
     * said. [isWaiting] when the relayer asked to wait longer than it takes to wait out here, or couldn't be reached:
     * the money waits safely at the deposit address.
     */
    data class Retry(
        val reason: String,
        val after: Duration?,
        val isWaiting: Boolean,
    ) : DepositAnswer
}

/**
 * A relayer's deposits over HTTP, on a client that never retries: the relayer records each deposit before it sends
 * it, so a post that timed out may have sent, and posting the same bytes again names what it sent. A `503` asking
 * 30 s or less is waited out and posted again, for up to 90 s in all, as zecswap-client's `BUSY_WAIT` does.
 */
class DepositsClient(
    http: HttpClient,
    baseUrl: Url,
) : DepositsRelayer {
    private val service = SwapService(http, baseUrl, AtomicSwapService.RELAYER)

    override suspend fun terms(): RelayerTerms =
        service.get("/v1/terms", RelayerTerms.serializer()) { it.requireWellFormed() }

    override suspend fun shield(request: ShieldDeposit): DepositAnswer =
        post("/v1/deposits/shield", request, ShieldDeposit.serializer())

    override suspend fun rescue(request: RescueDeposit): DepositAnswer =
        post("/v1/deposits/rescue", request, RescueDeposit.serializer())

    private suspend fun <T> post(
        path: String,
        request: T,
        serializer: KSerializer<T>,
    ): DepositAnswer =
        try {
            val sent = service.post(path, request, serializer, Sent.serializer(), isPatient = true)
            // An empty list names nothing: as no answer.
            if (sent.transactions.isEmpty()) {
                DepositAnswer.Retry("the relayer named no transaction", after = null, isWaiting = false)
            } else {
                DepositAnswer.Sent(sent.transactions)
            }
        } catch (e: AtomicSwapHttpException.Refused) {
            answer(e)
        } catch (e: AtomicSwapHttpException) {
            DepositAnswer.Retry(e.message.orEmpty(), after = null, isWaiting = true)
        }

    private fun answer(refusal: AtomicSwapHttpException.Refused): DepositAnswer =
        when (refusal.status) {
            HttpStatusCode.Conflict.value -> {
                DepositAnswer.Conflict(refusal.transactions)
            }

            in REFUSALS -> {
                DepositAnswer.Refused(refusal.status, refusal.message.orEmpty())
            }

            else -> {
                val after = refusal.retryAfter?.coerceAtMost(LONGEST_RETRY_AFTER)
                DepositAnswer.Retry(refusal.message.orEmpty(), after, isWaiting = true)
            }
        }

    private companion object {
        // 400 rejected or invalidRequest, 413 from the gateway for a body over 16 KiB, 415 and 422 invalidRequest.
        val REFUSALS =
            setOf(
                HttpStatusCode.BadRequest.value,
                HttpStatusCode.PayloadTooLarge.value,
                HttpStatusCode.UnsupportedMediaType.value,
                HttpStatusCode.UnprocessableEntity.value,
            )

        // The longest the relayer asks, the rest of the hour it holds an address back after a deposit reverted.
        val LONGEST_RETRY_AFTER = 1.hours
    }
}
