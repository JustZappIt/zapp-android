// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Peer's GraphQL indexer. No auth.
 *
 * The chain is the source of truth and this is how the app reads it: because the depositor is the
 * smart account, every order the user has ever opened is recoverable from one query, on any device,
 * even after a reinstall. Never build a local list of orders as the primary record.
 */
class PeerIndexerClient(
    private val httpClient: HttpClient,
    private val indexerUrl: String,
) {
    suspend fun order(id: PeerDepositId): PeerOrderSnapshot? {
        val data = query(ORDER_QUERY, buildJsonObject { put(VAR_ID, id.composite) })
        val row = data.requiredRows(FIELD_DEPOSIT).firstOrNull()?.jsonObject ?: return null
        return parseDeposit(row)
    }

    suspend fun activeOrdersFor(depositor: Address): List<PeerOrderSnapshot> =
        ordersFor(ACTIVE_DEPOSITS_BY_DEPOSITOR_QUERY, depositor)

    /**
     * Closed orders included. Reconciliation has to find a deposit that was created and immediately
     * taken, and an ACTIVE filter would report that one as never having existed.
     */
    suspend fun allOrdersFor(depositor: Address): List<PeerOrderSnapshot> =
        ordersFor(ALL_DEPOSITS_BY_DEPOSITOR_QUERY, depositor)

    /**
     * The depositor filter is case-insensitive because the indexer stores the address EIP-55
     * checksummed while every address in this codebase is lowercase, so a case-sensitive `_eq`
     * matches nothing: "you have no orders" on a live one, and no deposit for a reconcile to find.
     *
     * Every other filter here compares a hash the indexer also stores lowercase, so this is the only
     * one that needs it.
     */
    private suspend fun ordersFor(document: String, depositor: Address): List<PeerOrderSnapshot> {
        val data = query(document, buildJsonObject { put(VAR_DEPOSITOR, depositor.lowercaseHex) })
        return data.requiredRows(FIELD_DEPOSIT).map { parseDeposit(it.jsonObject) }
    }

    /**
     * Deposits on the pair joined with their first intent. Deliberately not `fillLatencySeconds`,
     * which measures signal to proof — the buyer's payment speed, not what the user is waiting on.
     *
     * Filtered on the currency as well as the rail, because that is the pair the band is labelled
     * with, and on [maturedBeforeSeconds] so the row budget goes to deposits old enough to have
     * resolved rather than to the newest ones, which are the least likely to have filled.
     */
    suspend fun queueSamples(
        platform: PeerPlatform,
        currency: PeerCurrency,
        maturedBeforeSeconds: Long,
        limit: Int = QUEUE_SAMPLE_LIMIT,
    ): List<PeerQueueSample> {
        val data =
            query(
                QUEUE_SAMPLES_QUERY,
                buildJsonObject {
                    put(VAR_PAYMENT_METHOD, platform.paymentMethodHash.hex)
                    put(VAR_CURRENCY, currency.codeHash.hex)
                    put(VAR_BEFORE, maturedBeforeSeconds)
                    put(VAR_LIMIT, limit)
                },
            )
        return data[FIELD_DEPOSIT]
            ?.jsonArray
            ?.mapNotNull { element ->
                val row = element.jsonObject
                val timestamp = row.long(FIELD_TIMESTAMP) ?: return@mapNotNull null
                PeerQueueSample(
                    depositTimestampSeconds = timestamp,
                    firstSignalTimestampSeconds =
                        row[FIELD_INTENTS]
                            ?.jsonArray
                            ?.firstOrNull()
                            ?.jsonObject
                            ?.long(FIELD_SIGNAL_TIMESTAMP),
                )
            }.orEmpty()
    }

    suspend fun fillSamples(platform: PeerPlatform, sinceSeconds: Long): List<PeerFillSample> {
        val data =
            query(
                FILL_SAMPLES_QUERY,
                buildJsonObject {
                    put(VAR_PAYMENT_METHOD, platform.paymentMethodHash.hex)
                    put(VAR_SINCE, sinceSeconds)
                    put(VAR_LIMIT, FILL_SAMPLE_LIMIT)
                },
            )
        return data[FIELD_INTENT]
            ?.jsonArray
            ?.mapNotNull { element ->
                val row = element.jsonObject
                PeerFillSample(
                    currency = row.string(FIELD_PAYMENT_CURRENCY)?.let(PeerCurrency::fromHashOrNull),
                    amount = row.usdc(FIELD_AMOUNT),
                    signalTimestampSeconds = row.long(FIELD_SIGNAL_TIMESTAMP) ?: return@mapNotNull null,
                )
            }.orEmpty()
    }

    private fun parseDeposit(row: JsonObject): PeerOrderSnapshot {
        val id =
            row.string(FIELD_ID)?.let(PeerDepositId::parseOrNull)
                ?: throw PeerErrorCode.INVALID_DEPOSIT_ID.asException()
        val paymentMethod = row[FIELD_PAYMENT_METHODS]?.jsonArray?.firstOrNull()?.jsonObject
        return PeerOrderSnapshot(
            id = id,
            status =
                PeerDepositStatus.fromWireOrNull(row.string(FIELD_STATUS))
                    ?: throw PeerErrorCode.INDEXER_UNAVAILABLE.asException(),
            acceptingIntents = row[FIELD_ACCEPTING_INTENTS]?.jsonPrimitive?.booleanOrNull ?: false,
            remaining = row.usdc(FIELD_REMAINING),
            outstandingIntentAmount = row.usdc(FIELD_OUTSTANDING),
            totalAmountTaken = row.usdc(FIELD_TOTAL_TAKEN),
            totalWithdrawn = row.usdc(FIELD_TOTAL_WITHDRAWN),
            intentAmountMin = row.usdc(FIELD_INTENT_MIN),
            intentAmountMax = row.usdc(FIELD_INTENT_MAX),
            signaledIntents = row.int(FIELD_SIGNALED_INTENTS),
            fulfilledIntents = row.int(FIELD_FULFILLED_INTENTS),
            prunedIntents = row.int(FIELD_PRUNED_INTENTS),
            platform = paymentMethod?.string(FIELD_PAYMENT_METHOD_HASH)?.let(PeerPlatform::fromPaymentMethodHashOrNull),
            payeeHash = paymentMethod?.string(FIELD_PAYEE_DETAILS_HASH)?.let(PayeeHash::parseOrNull),
            currencies = row[FIELD_CURRENCIES]?.jsonArray?.map { parseCurrency(it.jsonObject) }.orEmpty(),
            intents = row[FIELD_INTENTS]?.jsonArray?.map { parseIntent(it.jsonObject) }.orEmpty(),
            creationTxHash = row.txHash(FIELD_TX_HASH),
            creationBlockNumber = row.long(FIELD_BLOCK_NUMBER),
            openedAtSeconds = row.long(FIELD_TIMESTAMP),
            lastActivityAtSeconds = row.long(FIELD_UPDATED_AT),
            totalIntents = row.int(FIELD_TOTAL_INTENTS),
        )
    }

    private fun parseCurrency(row: JsonObject): PeerOrderCurrency =
        PeerOrderCurrency(
            currency = row.string(FIELD_CURRENCY_CODE)?.let(PeerCurrency::fromHashOrNull),
            spread = Bps(row.int(FIELD_SPREAD_BPS)),
            oracleRate = row.string(FIELD_ORACLE_RATE)?.let { runCatching { Rate1e18.parse(it) }.getOrNull() },
            lastOracleUpdatedAtSeconds = row.long(FIELD_LAST_ORACLE_UPDATED_AT),
        )

    private fun parseIntent(row: JsonObject): PeerIntent =
        PeerIntent(
            intentHash = row.string(FIELD_INTENT_HASH).orEmpty(),
            status = PeerIntentStatus.fromWire(row.string(FIELD_STATUS)),
            amount = row.usdc(FIELD_AMOUNT),
            releasedAmount = row.usdc(FIELD_RELEASED_AMOUNT),
            conversionRate = row.string(FIELD_CONVERSION_RATE)?.let { runCatching { Rate1e18.parse(it) }.getOrNull() },
            paymentCurrency = row.string(FIELD_PAYMENT_CURRENCY)?.let(PeerCurrency::fromHashOrNull),
            paymentAmount = PeerFiat.parseOrZero(row.string(FIELD_PAYMENT_AMOUNT)),
            paymentId = row.string(FIELD_PAYMENT_ID),
            signalTimestampSeconds = row.long(FIELD_SIGNAL_TIMESTAMP),
            // The only millisecond field on the schema; every other time on it is seconds.
            paymentTimestampSeconds = row.long(FIELD_PAYMENT_TIMESTAMP)?.div(MILLIS_PER_SECOND),
            fulfillTimestampSeconds = row.long(FIELD_FULFILL_TIMESTAMP),
            pruneTimestampSeconds = row.long(FIELD_PRUNE_TIMESTAMP),
            expiryTimeSeconds = row.long(FIELD_EXPIRY_TIME),
            isExpired = row[FIELD_IS_EXPIRED]?.jsonPrimitive?.booleanOrNull ?: false,
            fillLatencySeconds = row.long(FIELD_FILL_LATENCY)?.toInt(),
            signalTxHash = row.txHash(FIELD_SIGNAL_TX_HASH),
            fulfillTxHash = row.txHash(FIELD_FULFILL_TX_HASH),
            pruneTxHash = row.txHash(FIELD_PRUNE_TX_HASH),
        )

    /**
     * A GraphQL error arrives as HTTP 200. Checking the status alone would treat a broken query as
     * an empty result, which renders as "order not found" on a live order, so `errors` is parsed
     * first, always.
     */
    private suspend fun query(document: String, variables: JsonElement): JsonObject {
        val response: JsonObject =
            runPeerCatching {
                httpClient
                    .post(indexerUrl) {
                        contentType(ContentType.Application.Json)
                        setBody(
                            buildJsonObject {
                                put(FIELD_QUERY, document)
                                put(FIELD_VARIABLES, variables)
                            },
                        )
                    }.body<JsonObject>()
            }.getOrElse { throw PeerErrorCode.INDEXER_UNAVAILABLE.asException(cause = it) }

        val errors = response[FIELD_ERRORS]?.jsonArray
        val data = response[FIELD_DATA]?.jsonObject
        return if (errors.isNullOrEmpty() && data != null) {
            data
        } else {
            throw PeerErrorCode.INDEXER_UNAVAILABLE.asException()
        }
    }

    private fun JsonObject.requiredRows(field: String): JsonArray =
        this[field] as? JsonArray ?: throw PeerErrorCode.INDEXER_UNAVAILABLE.asException()

    companion object {
        const val QUEUE_SAMPLE_LIMIT = 120
        const val FILL_SAMPLE_LIMIT = 500

        private const val VAR_ID = "id"
        private const val VAR_DEPOSITOR = "depositor"
        private const val VAR_PAYMENT_METHOD = "pm"
        private const val VAR_CURRENCY = "cur"
        private const val VAR_BEFORE = "before"
        private const val VAR_SINCE = "since"
        private const val VAR_LIMIT = "limit"

        private const val FIELD_QUERY = "query"
        private const val FIELD_VARIABLES = "variables"
        private const val FIELD_DATA = "data"
        private const val FIELD_ERRORS = "errors"
        private const val FIELD_DEPOSIT = "Deposit"
        private const val FIELD_INTENT = "Intent"
        private const val FIELD_ID = "id"
        private const val FIELD_STATUS = "status"
        private const val FIELD_ACCEPTING_INTENTS = "acceptingIntents"
        private const val FIELD_REMAINING = "remainingDeposits"
        private const val FIELD_OUTSTANDING = "outstandingIntentAmount"
        private const val FIELD_TOTAL_TAKEN = "totalAmountTaken"
        private const val FIELD_TOTAL_WITHDRAWN = "totalWithdrawn"
        private const val FIELD_INTENT_MIN = "intentAmountMin"
        private const val FIELD_INTENT_MAX = "intentAmountMax"
        private const val FIELD_SIGNALED_INTENTS = "signaledIntents"
        private const val FIELD_FULFILLED_INTENTS = "fulfilledIntents"
        private const val FIELD_PRUNED_INTENTS = "prunedIntents"
        private const val FIELD_PAYMENT_METHODS = "paymentMethods"
        private const val FIELD_PAYMENT_METHOD_HASH = "paymentMethodHash"
        private const val FIELD_PAYEE_DETAILS_HASH = "payeeDetailsHash"
        private const val FIELD_CURRENCIES = "currencies"
        private const val FIELD_CURRENCY_CODE = "currencyCode"
        private const val FIELD_SPREAD_BPS = "spreadBps"
        private const val FIELD_ORACLE_RATE = "oracleRate"
        private const val FIELD_LAST_ORACLE_UPDATED_AT = "lastOracleUpdatedAt"
        private const val FIELD_INTENTS = "intents"
        private const val FIELD_INTENT_HASH = "intentHash"
        private const val FIELD_AMOUNT = "amount"
        private const val FIELD_RELEASED_AMOUNT = "releasedAmount"
        private const val FIELD_CONVERSION_RATE = "conversionRate"
        private const val FIELD_PAYMENT_CURRENCY = "paymentCurrency"
        private const val FIELD_PAYMENT_AMOUNT = "paymentAmount"
        private const val FIELD_PAYMENT_ID = "paymentId"
        private const val FIELD_SIGNAL_TIMESTAMP = "signalTimestamp"
        private const val FIELD_PAYMENT_TIMESTAMP = "paymentTimestamp"
        private const val FIELD_FULFILL_TIMESTAMP = "fulfillTimestamp"
        private const val FIELD_PRUNE_TIMESTAMP = "pruneTimestamp"
        private const val FIELD_EXPIRY_TIME = "expiryTime"
        private const val FIELD_IS_EXPIRED = "isExpired"
        private const val FIELD_FILL_LATENCY = "fillLatencySeconds"
        private const val FIELD_TIMESTAMP = "timestamp"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val FIELD_TOTAL_INTENTS = "totalIntents"
        private const val FIELD_TX_HASH = "txHash"
        private const val FIELD_SIGNAL_TX_HASH = "signalTxHash"
        private const val FIELD_FULFILL_TX_HASH = "fulfillTxHash"
        private const val FIELD_PRUNE_TX_HASH = "pruneTxHash"
        private const val FIELD_BLOCK_NUMBER = "blockNumber"
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * The nested selection is a page, and an unordered one is an arbitrary page: the buyer list
         * would show a random subset and the expired-intent total would sum a random subtotal.
         * Newest first, because both of those describe what is happening now. Far above any real
         * order, where the intent floor puts a few dozen buyers on the largest cash-out.
         */
        const val INTENT_PAGE_LIMIT = 100

        private const val DEPOSIT_FIELDS = """
                id depositId escrowAddress status acceptingIntents txHash blockNumber
                timestamp updatedAt totalIntents
                remainingDeposits outstandingIntentAmount totalAmountTaken totalWithdrawn
                intentAmountMin intentAmountMax signaledIntents fulfilledIntents prunedIntents
                paymentMethods { paymentMethodHash payeeDetailsHash active }
                currencies { currencyCode spreadBps kind oracleRate lastOracleUpdatedAt }
                intents(order_by: { signalTimestamp: desc }, limit: $INTENT_PAGE_LIMIT) {
                          intentHash status amount owner conversionRate fiatCurrency
                          paymentAmount paymentCurrency paymentId releasedAmount
                          signalTimestamp paymentTimestamp fulfillTimestamp pruneTimestamp
                          expiryTime isExpired fillLatencySeconds
                          signalTxHash fulfillTxHash pruneTxHash }"""

        const val ORDER_QUERY = """
            query Order(${'$'}id: String!) {
              Deposit(where: { id: { _eq: ${'$'}id } }) {$DEPOSIT_FIELDS
              }
            }
        """

        const val ACTIVE_DEPOSITS_BY_DEPOSITOR_QUERY = """
            query ActiveDepositsByDepositor(${'$'}depositor: String!) {
              Deposit(
                where: { depositor: { _ilike: ${'$'}depositor }, status: { _eq: "ACTIVE" } }
                order_by: { timestamp: desc }
              ) {$DEPOSIT_FIELDS
              }
            }
        """

        const val ALL_DEPOSITS_BY_DEPOSITOR_QUERY = """
            query DepositsByDepositor(${'$'}depositor: String!) {
              Deposit(
                where: { depositor: { _ilike: ${'$'}depositor } }
                order_by: { timestamp: desc }
              ) {$DEPOSIT_FIELDS
              }
            }
        """

        const val QUEUE_SAMPLES_QUERY = """
            query QueueSamples(
              ${'$'}pm: String!, ${'$'}cur: String!, ${'$'}before: numeric!, ${'$'}limit: Int!
            ) {
              Deposit(
                where: {
                  paymentMethods: { paymentMethodHash: { _eq: ${'$'}pm } }
                  currencies: { currencyCode: { _eq: ${'$'}cur } }
                  timestamp: { _lte: ${'$'}before }
                }
                order_by: { timestamp: desc }
                limit: ${'$'}limit
              ) {
                timestamp
                intents(order_by: { signalTimestamp: asc }, limit: 1) { signalTimestamp }
              }
            }
        """

        const val FILL_SAMPLES_QUERY = """
            query FillSamples(${'$'}pm: String!, ${'$'}since: numeric!, ${'$'}limit: Int!) {
              Intent(
                where: {
                  paymentMethodHash: { _eq: ${'$'}pm }
                  status: { _eq: "FULFILLED" }
                  signalTimestamp: { _gte: ${'$'}since }
                }
                limit: ${'$'}limit
              ) {
                paymentCurrency amount signalTimestamp
              }
            }
        """
    }
}

private const val NULL_LITERAL = "null"

// The indexer returns every numeric as a JSON string, and an absent field as the literal "null",
// so every read goes through the same narrow accessors rather than trusting the primitive type.
private fun JsonObject.string(field: String): String? =
    this[field]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() && it != NULL_LITERAL }

private fun JsonObject.long(field: String): Long? = string(field)?.toLongOrNull()

private fun JsonObject.int(field: String): Int = string(field)?.toIntOrNull() ?: 0

private fun JsonObject.usdc(field: String): Usdc6 =
    string(field)?.let { runCatching { Usdc6.ofMicros(BigInteger(it)) }.getOrNull() } ?: Usdc6.ZERO

private fun JsonObject.txHash(field: String): TxHash? =
    string(field)?.let { runCatching { TxHash.fromHex(it) }.getOrNull() }
