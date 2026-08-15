// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Deliberately small. The chain is the source of truth and the checkpoint is a cache, so this
 * covers only the two things the chain cannot answer:
 *
 *  1. the NEAR 1-Click bridge handle, which exists nowhere on Base;
 *  2. the window between broadcasting `createDeposit` and the indexer showing the deposit.
 *
 * Once the deposit is indexed this is cleared, and from then on the whole order is recoverable from
 * the indexer with the smart account alone — on any device, after any reinstall.
 *
 * The payee handle is never stored. It is PII, and the hash is all the protocol needs.
 *
 * [id] is what keeps concurrent attempts apart. Several cash-outs can be unfinished at once, and an
 * anonymous record cannot say which one a transaction hash belongs to.
 */
@Serializable
data class PeerCashOutCheckpoint(
    val id: PeerCashOutId,
    val platform: PeerPlatform,
    val currencies: List<PeerCurrency>,
    val payeeHashHex: String,
    val amountMicroDecimal: String,
    val bridgeDepositAddress: String? = null,
    val approveTxHash: TxHash? = null,
    val createDepositTxHash: TxHash? = null,
    val blockBeforeCreateDeposit: String? = null,
    val depositId: PeerDepositId? = null,
    val createdAtMillis: Long,
) {
    init {
        require(currencies.isNotEmpty()) { "checkpoint must carry at least one currency" }
        require(runCatching { PayeeHash.parse(payeeHashHex) }.isSuccess) {
            "PeerCashOutCheckpoint.payeeHashHex must be a 32-byte hash"
        }
        require(runCatching { BigInteger(amountMicroDecimal) }.isSuccess) {
            "PeerCashOutCheckpoint.amountMicroDecimal must be a decimal integer"
        }
    }

    val payeeHash: PayeeHash get() = PayeeHash.parse(payeeHashHex)

    val amount: Usdc6 get() = Usdc6(BigInteger(amountMicroDecimal))

    val resumeAction: PeerResumeAction
        get() =
            when {
                depositId != null -> {
                    PeerResumeAction.ReadOrder(depositId)
                }

                createDepositTxHash != null -> {
                    PeerResumeAction.ResolveSubmittedDeposit(createDepositTxHash)
                }

                // A send that returned no hash may still have broadcast, so [blockFloor] becomes the
                // floor to look the order up from. Sending again escrows a second lot.
                blockBeforeCreateDeposit != null -> {
                    PeerResumeAction.ReconcileSubmission
                }

                bridgeDepositAddress != null -> {
                    PeerResumeAction.ResumeBridge(bridgeDepositAddress)
                }

                else -> {
                    PeerResumeAction.FreshStart
                }
            }

    /** False once nothing here needs resolving, which is the point at which it is not worth storing. */
    val isRecoverable: Boolean get() = resumeAction != PeerResumeAction.FreshStart

    /**
     * Whether [amount] is still sitting in the smart account. Once a deposit exists the escrow holds
     * it and the balance already reflects that, so counting it again would hide funds from the user.
     */
    val holdsUnescrowedFunds: Boolean get() = depositId == null

    val blockFloor: Long? get() = blockBeforeCreateDeposit?.toLongOrNull()

    companion object {
        fun of(
            id: PeerCashOutId,
            request: PeerCashOutRequest,
            payeeHash: PayeeHash,
            createdAtMillis: Long,
        ): PeerCashOutCheckpoint =
            PeerCashOutCheckpoint(
                id = id,
                platform = request.platform,
                currencies = request.currencies,
                payeeHashHex = payeeHash.hex,
                amountMicroDecimal = request.amount.micros.toString(),
                createdAtMillis = createdAtMillis,
            )
    }
}

/**
 * What a resume does, in order, never re-broadcasting. Re-running `createDeposit` on an uncertain
 * receipt escrows a second lot of USDC, so the hash-only case resolves rather than resends.
 */
sealed interface PeerResumeAction {
    data class ReadOrder(
        val depositId: PeerDepositId,
    ) : PeerResumeAction

    data class ResolveSubmittedDeposit(
        val txHash: TxHash,
    ) : PeerResumeAction

    /**
     * A `createDeposit` was attempted and its hash never came back. Look it up from
     * [PeerCashOutCheckpoint.blockFloor], never resend.
     */
    data object ReconcileSubmission : PeerResumeAction

    data class ResumeBridge(
        val depositAddress: String,
    ) : PeerResumeAction

    data object FreshStart : PeerResumeAction
}

/**
 * Everything the user chose, validated once so an invalid order cannot reach the chain.
 *
 * The payee is either a [handle] to register or an already-registered [cachedPayeeHash]. Recovering
 * an attempt from its checkpoint has only the hash — the handle is PII and is never persisted — and
 * that is enough, because registration is the one step the hash makes unnecessary.
 */
data class PeerCashOutRequest(
    val platform: PeerPlatform,
    val handle: PayeeHandle?,
    val currencies: List<PeerCurrency>,
    val amount: Usdc6,
    /** Registration is per handle, so a previously registered one skips the curator round trip. */
    val cachedPayeeHash: PayeeHash? = null,
) {
    init {
        require(handle != null || cachedPayeeHash != null) { "a cash-out needs a handle or a registered hash" }
        require(currencies.isNotEmpty()) { "pick at least one currency" }
        require(currencies.size == currencies.toSet().size) { "currencies must be unique" }
        require(currencies.all { it in platform.currencies }) {
            "currency not offered on ${platform.wireName}"
        }
        require(amount.micros >= BigInteger(PeerNetworks.MIN_CASHOUT_MICROS.toString())) {
            "amount is below the protocol floor"
        }
    }
}
