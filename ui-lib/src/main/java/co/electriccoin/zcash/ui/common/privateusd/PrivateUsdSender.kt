// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.backgroundScope
import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.railgun.RailgunDestination
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunSignedTransaction
import xyz.justzappit.railgun.RailgunTransfer
import java.math.BigInteger
import java.util.UUID
import kotlin.time.Clock

data class PrivateUsdSendRequest(
    val token: PrivateUsdToken,
    val amount: BigInteger,
    val to: RailgunDestination,
) {
    val isWithdrawal: Boolean get() = to is RailgunDestination.Public
}

data class PrivateUsdSendCost(
    /** Railgun's fee on a withdrawal, taken from the amount sent. */
    val railgunFee: BigInteger,
    val feeBasisPoints: Int,
) {
    fun received(amount: BigInteger): BigInteger = amount - railgunFee
}

sealed interface PrivateUsdSendOutcome {
    /** In a block. */
    data class Sent(
        val txHash: TxHash
    ) : PrivateUsdSendOutcome

    /** Handed over but not seen in a block: it may still land, so it must never be sent again. */
    data class Unconfirmed(
        val txHash: TxHash
    ) : PrivateUsdSendOutcome

    /** Nothing left the wallet. */
    data object NotSent : PrivateUsdSendOutcome

    /** An earlier payment is unresolved; no additional transaction was created. */
    data object Busy : PrivateUsdSendOutcome
}

/** Sends from the Railgun balance. */
interface PrivateUsdSender {
    suspend fun cost(request: PrivateUsdSendRequest): PrivateUsdSendCost

    /** Runs to its end, and into the send log, even once its caller stops waiting. */
    suspend fun send(request: PrivateUsdSendRequest): PrivateUsdSendOutcome

    /** Settles what the log still holds unconfirmed: confirmed, failed, or sent again if the node lost it. */
    fun reconcile()

    /** Stops every send and settlement, and waits until they have, for a wallet about to be wiped. */
    suspend fun reset()
}

/** Pays gas from a funded Sepolia account, which links each send to that account: testnets only. */
class TestnetGasAccountSender(
    private val railgunWalletRepository: RailgunWalletRepository,
    private val transactions: GasAccountTransactions,
    private val sendLog: PrivateUsdSendLog,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val spendGuard: PrivateUsdSpendGuard,
) : PrivateUsdSender {
    private val settlement = PrivateUsdSendSettlement(transactions, sendLog)

    override suspend fun cost(request: PrivateUsdSendRequest): PrivateUsdSendCost =
        if (request.isWithdrawal) {
            val fees = railgunWalletRepository.fees()
            PrivateUsdSendCost(fees.unshieldFee(request.amount), fees.unshieldBasisPoints)
        } else {
            PrivateUsdSendCost(BigInteger.ZERO, 0)
        }

    override suspend fun send(request: PrivateUsdSendRequest): PrivateUsdSendOutcome =
        scope.async { spendGuard.send { sendNow(request) } }.await()

    override fun reconcile() {
        scope.launch { settlement.settleAll() }
    }

    override suspend fun reset() {
        val work = scope.coroutineContext.job
        work.cancelChildren()
        work.children.forEach { it.join() }
    }

    private suspend fun sendNow(request: PrivateUsdSendRequest): PrivateUsdSendOutcome {
        val pending =
            PrivateUsdPendingSend(
                id = UUID.randomUUID().toString(),
                token = request.token.address,
                amount = request.amount,
                to = request.to,
                startedAt = clock.now().epochSeconds,
            )
        noted { sendLog.begin(pending) }
        val signed = signAndKeep(pending, request) ?: return PrivateUsdSendOutcome.NotSent
        val outcome = deliver(pending, signed)
        // The balance follows the send without the send waiting for it.
        scope.launch { bestEffort("Private USD: no sync after the send") { railgunWalletRepository.sync() } }
        return outcome
    }

    // Null when nothing was signed, or what was couldn't be kept to send again: then nothing goes out.
    private suspend fun signAndKeep(
        pending: PrivateUsdPendingSend,
        request: PrivateUsdSendRequest
    ): RailgunSignedTransaction? {
        val signed =
            runCatching {
                railgunWalletRepository
                    .sign(RailgunTransfer(request.to, request.token.address, request.amount))
                    .also { sendLog.sign(pending, it, clock.now().epochSeconds) }
            }
        signed.exceptionOrNull()?.let { e ->
            // Only this send's own cancellation stops it; the engine's, in a reset, is a failure.
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            Twig.warn(e) { "Private USD: nothing was sent" }
            noted { sendLog.remove(pending, null) }
        }
        return signed.getOrNull()
    }

    private suspend fun deliver(
        pending: PrivateUsdPendingSend,
        signed: RailgunSignedTransaction
    ): PrivateUsdSendOutcome =
        when (deliveryOf(signed)) {
            GasAccountDelivery.CONFIRMED -> {
                noted { sendLog.confirm(signed.txHash) }
                PrivateUsdSendOutcome.Sent(signed.txHash)
            }

            GasAccountDelivery.UNCONFIRMED -> {
                PrivateUsdSendOutcome.Unconfirmed(signed.txHash)
            }

            GasAccountDelivery.FAILED -> {
                noted { sendLog.remove(pending, signed.txHash) }
                PrivateUsdSendOutcome.NotSent
            }
        }

    // Once signed and kept, a send that fails any way may have gone out: settling it later tells.
    private suspend fun deliveryOf(signed: RailgunSignedTransaction): GasAccountDelivery {
        val delivery = runCatching { transactions.deliver(signed.raw, signed.txHash) }
        delivery.exceptionOrNull()?.let { e ->
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            Twig.warn(e) { "Private USD: ${signed.txHash} may have been sent" }
        }
        return delivery.getOrDefault(GasAccountDelivery.UNCONFIRMED)
    }

    // The log is a record, not a gate, once a send is signed and kept: what follows goes ahead regardless.
    private suspend fun noted(block: suspend () -> Unit) {
        bestEffort("Private USD: the send log wasn't updated", block)
    }
}

/** Settles the sends the log still holds unconfirmed, one pass at a time: confirmed, failed, or sent again. */
private class PrivateUsdSendSettlement(
    private val transactions: GasAccountTransactions,
    private val sendLog: PrivateUsdSendLog,
) {
    private val lock = Mutex()

    // A pass asked for while one runs has nothing left to do.
    suspend fun settleAll() {
        if (!lock.tryLock()) return
        try {
            sendLog.unconfirmed().forEach { settle(it) }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun settle(send: PrivateUsdSendRecord) {
        bestEffort("Private USD: ${send.txHash} wasn't settled") {
            val kept = checkNotNull(send.signed) { "an unconfirmed send keeps its transaction" }
            when (transactions.reconcile(kept.raw, send.txHash, kept.from, kept.nonce)) {
                GasAccountDelivery.CONFIRMED -> sendLog.confirm(send.txHash)
                GasAccountDelivery.FAILED -> sendLog.remove(send.txHash)
                GasAccountDelivery.UNCONFIRMED -> Unit
            }
        }
    }
}

/** The sender this build has; none until broadcasters exist for its network. */
class PrivateUsdSenders(
    railgunWalletRepository: RailgunWalletRepository,
    transactions: GasAccountTransactions,
    sendLog: PrivateUsdSendLog,
    spendGuard: PrivateUsdSpendGuard,
) {
    val current: PrivateUsdSender? =
        when (railgunWalletRepository.state.value.network) {
            RailgunNetwork.SEPOLIA -> {
                TestnetGasAccountSender(
                    railgunWalletRepository = railgunWalletRepository,
                    transactions = transactions,
                    sendLog = sendLog,
                    scope = backgroundScope("Private USD sends"),
                    clock = Clock.System,
                    spendGuard = spendGuard,
                )
            }

            RailgunNetwork.MAINNET, null -> {
                null
            }
        }
}
