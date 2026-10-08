// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import xyz.justzappit.evm.types.TxHash

/** A relayer as the broadcaster of wallets' Railgun sends and withdrawals: it pays their gas for a fee note. */
interface RailgunSendRelayer {
    suspend fun terms(): RelayerTerms

    suspend fun transact(request: RailgunTransactRequest): RailgunBroadcast
}

/** What a relayer did with a Railgun transaction it was asked to send. */
sealed interface RailgunBroadcast {
    /** Submitted in [txHash], not yet mined: the same bytes posted again name it. */
    data class Sent(
        val txHash: TxHash
    ) : RailgunBroadcast

    /** Refused: nothing from this proof was sent, and nothing will be. */
    data class Refused(
        val reason: String
    ) : RailgunBroadcast

    /**
     * A note it spends is spent, or a transaction already sent spends it: what happened is read from the chain. The
     * relayer names its own [transactions] that spend them, if it knows any.
     */
    data class Spent(
        val transactions: List<TxHash>
    ) : RailgunBroadcast

    /** Not known yet, or never answered: the same bytes go again later. */
    data class Retry(
        val reason: String
    ) : RailgunBroadcast
}

/** A relayer's Railgun sends over HTTP. It must not retry: a post that timed out may have sent its transaction. */
class RailgunSendsClient(
    http: HttpClient,
    baseUrl: Url,
) : RailgunSendRelayer {
    private val service = SwapService(http, baseUrl, AtomicSwapService.RELAYER)

    override suspend fun terms(): RelayerTerms =
        service.get("/v1/terms", RelayerTerms.serializer()) { it.requireWellFormed() }

    // As zecswap-client's RelayerApi::railgun_transact: a 409 with its code is spent, any other 4xx refused, and
    // anything else, a lost answer included, unknown.
    override suspend fun transact(request: RailgunTransactRequest): RailgunBroadcast =
        try {
            service
                .post("/v1/railgun/transact", request, RailgunTransactRequest.serializer(), Sent.serializer())
                .transactions
                .firstOrNull()
                ?.let { RailgunBroadcast.Sent(it) }
                ?: RailgunBroadcast.Retry("the relayer named no transaction")
        } catch (e: AtomicSwapHttpException.Refused) {
            val conflict = HttpStatusCode.Conflict.value
            when {
                e.status == conflict && e.code == SwapErrorCode.ALREADY_SPENT -> {
                    RailgunBroadcast.Spent(e.transactions)
                }

                e.status in FIRST_CLIENT_ERROR..LAST_CLIENT_ERROR && e.status != conflict -> {
                    RailgunBroadcast.Refused(e.message.orEmpty())
                }

                else -> {
                    RailgunBroadcast.Retry(e.message.orEmpty())
                }
            }
        } catch (e: AtomicSwapHttpException) {
            RailgunBroadcast.Retry(e.message.orEmpty())
        }

    private companion object {
        const val FIRST_CLIENT_ERROR = 400
        const val LAST_CLIENT_ERROR = 499
    }
}
