// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

interface ReverseSwapApi {
    suspend fun info(): ReverseMakerInfo

    suspend fun quote(units: Int, user: String, refundNote: String): ReverseQuote

    suspend fun accept(quoteId: String, acceptance: ReverseAcceptance): String

    suspend fun terms(): ReverseRelayerTerms

    suspend fun ready(authorization: ReverseAuthorization)

    suspend fun lockRefund(authorization: ReverseAuthorization)

    suspend fun refund(request: ReverseRefund)

    suspend fun payout(request: ReversePayout)

    suspend fun rescue(request: ReversePayout)
}

interface ReverseSwapKeys {
    suspend fun signOpen(record: ReverseSwapRecord): ByteArray

    suspend fun signReady(record: ReverseSwapRecord, deadline: Long): ByteArray

    suspend fun signLockRefund(record: ReverseSwapRecord, deadline: Long): ByteArray

    suspend fun signPayout(record: ReverseSwapRecord, terms: ReverseRelayerTerms): ByteArray

    suspend fun signRescue(record: ReverseSwapRecord, terms: ReverseRelayerTerms): ByteArray
}

interface ReverseSwapChain {
    suspend fun read(id: String): ReverseChainState

    suspend fun fundingStatus(txId: String): ReverseTransactionStatus

    suspend fun vaultBalance(id: String): String
}

interface ReverseSwapFunding {
    suspend fun cost(escrowAmount: String): ReverseFundingCost

    suspend fun prepare(record: ReverseSwapRecord, signature: ByteArray): ReverseFundingTransaction

    suspend fun submit(transaction: ReverseFundingTransaction)
}

interface ReverseSwapZcash {
    suspend fun height(): Long

    suspend fun importAccount(record: ReverseSwapRecord): String

    /** Syncs this exact joint account to the current tip before returning confirmed, spendable zatoshi. */
    suspend fun spendable(record: ReverseSwapRecord): Long

    suspend fun estimateReceive(record: ReverseSwapRecord): ReverseReceiveEstimate

    /** Creates locally or recovers a previously created sweep; never broadcasts. */
    suspend fun prepareReceive(record: ReverseSwapRecord, makerSecret: ByteArray): ReverseReceiveTransaction

    suspend fun submit(transaction: ReverseReceiveTransaction)

    suspend fun receiveStatus(record: ReverseSwapRecord): ReverseReceiveStatus
}

interface ReverseSwapStore {
    suspend fun active(): ReverseSwapRecord?

    suspend fun save(record: ReverseSwapRecord)
}
