package co.electriccoin.zcash.ui.screen.swap.peer.progress

import co.electriccoin.zcash.ui.common.provider.PeerCashOutCheckpointStorageProvider
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.peer.PayeeHash
import xyz.justzappit.offramp.peer.PeerCashOutCheckpoint
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerCashOutRequest
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerDepositId
import xyz.justzappit.offramp.peer.depositId

/**
 * Writes one attempt's checkpoint from its status stream, and clears it the moment the attempt is
 * settled either way: the deposit is indexed, or the send that would have opened it provably
 * reverted.
 *
 * The two values worth persisting arrive on statuses that are gone by the time the order exists —
 * the bridge handle and the block read immediately before `createDeposit` — so they are captured
 * eagerly as they fire. Nothing here holds the payee handle: only the curator hash.
 *
 * Everything it writes is scoped to [id]. Carrying a value forward from whatever happened to be
 * stored produces a record with one attempt's amount over another's transaction hashes.
 */
internal class PeerCashOutCheckpointPersister(
    private val storage: PeerCashOutCheckpointStorageProvider,
    private val id: PeerCashOutId,
    private val request: PeerCashOutRequest,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var payeeHash: PayeeHash? = null
    private var bridgeDepositAddress: String? = null
    private var approveTxHash: TxHash? = null
    private var createDepositSubmissionHash: TxHash? = null
    private var createDepositSubmissionNonceDecimal: String? = null
    private var createDepositTxHash: TxHash? = null
    private var blockBeforeCreateDeposit: String? = null
    private var depositId: PeerDepositId? = null
    private var createdAtMillis: Long? = null

    fun seedFrom(checkpoint: PeerCashOutCheckpoint?) {
        if (checkpoint == null || checkpoint.id != id) return
        payeeHash = checkpoint.payeeHash
        bridgeDepositAddress = checkpoint.bridgeDepositAddress
        approveTxHash = checkpoint.approveTxHash
        createDepositSubmissionHash = checkpoint.createDepositSubmissionHash
        createDepositSubmissionNonceDecimal = checkpoint.createDepositSubmissionNonceDecimal
        createDepositTxHash = checkpoint.createDepositTxHash
        blockBeforeCreateDeposit = checkpoint.blockBeforeCreateDeposit
        depositId = checkpoint.depositId
        createdAtMillis = checkpoint.createdAtMillis
    }

    fun rememberPayeeHash(hash: PayeeHash) {
        payeeHash = hash
    }

    suspend fun onStatus(status: PeerCashOutStatus) {
        capture(status)
        persistOrClear(status)
    }

    private fun capture(status: PeerCashOutStatus) {
        status.depositId?.let { depositId = it }
        when (status) {
            is PeerCashOutStatus.ValidatingPayee -> {
                status.payeeHash?.let { payeeHash = it }
            }

            is PeerCashOutStatus.BridgingFunds -> {
                status.depositAddress?.let { bridgeDepositAddress = it }
            }

            is PeerCashOutStatus.ApprovingUsdc -> {
                approveTxHash = status.txHash
            }

            is PeerCashOutStatus.CreatingDeposit -> {
                createDepositSubmissionHash = status.submissionHash
                status.submissionNonceDecimal?.let { createDepositSubmissionNonceDecimal = it }
                status.txHash?.let { createDepositTxHash = it }
            }

            else -> {
                Unit
            }
        }
    }

    private suspend fun persistOrClear(status: PeerCashOutStatus) {
        when {
            // From here the chain is the whole record: the order is recoverable from the indexer
            // with the smart account alone, on any device.
            status is PeerCashOutStatus.OrderLive || status is PeerCashOutStatus.Withdrawn -> {
                storage.clear(id)
            }

            status is PeerCashOutStatus.Failed && retires(status) -> {
                storage.clear(id)
            }

            else -> {
                persist()
            }
        }
    }

    /**
     * A `createDeposit` that provably reverted escrowed nothing: there is no order to resolve and no
     * funds left reserved, so the record is retired rather than kept. Leaving it behind is what makes
     * a failed attempt subtract its amount from Available until the wallet is wiped, and makes every
     * retry resolve the same reverted receipt.
     *
     * Only past the send. Earlier than that the bridge handle is still the sole record of ZEC in
     * flight, and a funding failure must not take it with it.
     */
    private fun retires(status: PeerCashOutStatus.Failed): Boolean =
        status.error.nothingEscrowed && hasAttemptedDeposit

    private val hasAttemptedDeposit: Boolean
        get() =
            depositId != null ||
                createDepositSubmissionHash != null ||
                createDepositTxHash != null ||
                blockBeforeCreateDeposit != null

    /**
     * Writes whenever there is something a resume would need, which crucially includes the block
     * read before `createDeposit` is sent: without it a send whose hash never came back leaves no
     * trace, and the next launch would broadcast a second deposit.
     *
     * A checkpoint with nothing to resolve is cleared rather than stored, so its mere existence
     * means an order is genuinely unfinished.
     */
    private suspend fun persist() {
        val hash = payeeHash ?: return
        val checkpoint =
            PeerCashOutCheckpoint(
                id = id,
                platform = request.platform,
                currencies = request.currencies,
                payeeHashHex = hash.hex,
                amountMicroDecimal = request.amount.micros.toString(),
                bridgeDepositAddress = bridgeDepositAddress,
                approveTxHash = approveTxHash,
                createDepositSubmissionHash = createDepositSubmissionHash,
                createDepositSubmissionNonceDecimal = createDepositSubmissionNonceDecimal,
                createDepositTxHash = createDepositTxHash,
                blockBeforeCreateDeposit = blockBeforeCreateDeposit,
                depositId = depositId,
                createdAtMillis = createdAtMillis ?: nowMillis().also { createdAtMillis = it },
            )
        if (checkpoint.isRecoverable) {
            storage.store(checkpoint)
        } else {
            storage.clear(id)
        }
    }
}
