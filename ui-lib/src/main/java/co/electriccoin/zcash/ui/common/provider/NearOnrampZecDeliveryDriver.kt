// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.SwapStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.signer.TxSubmitter
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.offramp.account.OfframpSmartAccount
import xyz.justzappit.offramp.onramp.FundsLocation
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryDriver
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryPhase
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
import xyz.justzappit.offramp.onramp.fundsLocation
import xyz.justzappit.offramp.p2p.Erc20Calls
import xyz.justzappit.offramp.p2p.Usdc6
import java.util.concurrent.atomic.AtomicReference

internal data class OnrampBaseTransferReceipt(
    val success: Boolean,
    val transactionHash: String,
)

internal interface OnrampZecTransferGateway {
    suspend fun resolveAccount(): Address

    suspend fun balance(account: Address): Usdc6

    suspend fun submit(account: Address, depositAddress: Address, amount: Usdc6): String

    suspend fun awaitReceipt(account: Address, userOperationHash: String): OnrampBaseTransferReceipt
}

internal fun interface OnrampZecDeliveryCheckpointStore {
    suspend fun save(orderId: String, checkpoint: OnrampZecDeliveryCheckpoint)
}

internal fun interface OnrampSmartAccountResolver {
    suspend fun resolve(): OfframpSmartAccount
}

internal fun interface OnrampUsdcBalanceReader {
    suspend fun balance(account: Address): Usdc6
}

internal fun interface OnrampSubmitterFactory {
    fun create(account: OfframpSmartAccount): TxSubmitter
}

internal class Erc4337OnrampZecTransferGateway(
    private val usdc: Address,
    private val accountResolver: OnrampSmartAccountResolver,
    private val balanceReader: OnrampUsdcBalanceReader,
    private val submitterFactory: OnrampSubmitterFactory,
) : OnrampZecTransferGateway {
    private val resolvedAccount = AtomicReference<OfframpSmartAccount?>()

    override suspend fun resolveAccount(): Address = account().address

    override suspend fun balance(account: Address): Usdc6 {
        require(account == account().address) { "Resolved smart account changed" }
        return balanceReader.balance(account)
    }

    override suspend fun submit(account: Address, depositAddress: Address, amount: Usdc6): String {
        val resolved = account()
        require(account == resolved.address) { "Resolved smart account changed" }
        require(amount > Usdc6.ZERO) { "Transfer amount must be positive" }
        return submitterFactory
            .create(resolved)
            .sendTransaction(
                to = usdc,
                value = Wei.ZERO,
                data = Erc20Calls.transferCalldata(depositAddress, amount),
            ).hex
    }

    override suspend fun awaitReceipt(account: Address, userOperationHash: String): OnrampBaseTransferReceipt {
        val resolved = account()
        require(account == resolved.address) { "Resolved smart account changed" }
        val receipt = submitterFactory.create(resolved).awaitReceipt(TxHash.fromHex(userOperationHash))
        return receipt.toOnrampReceipt()
    }

    private suspend fun account(): OfframpSmartAccount =
        resolvedAccount.get() ?: accountResolver.resolve().also(resolvedAccount::set)

    private fun TransactionReceipt.toOnrampReceipt() = OnrampBaseTransferReceipt(success, transactionHash)
}

@Suppress("TooManyFunctions")
internal class NearOnrampZecDeliveryDriver(
    private val transfer: OnrampZecTransferGateway,
    private val swap: OnrampZecSwapGateway,
    private val checkpoints: OnrampZecDeliveryCheckpointStore,
    private val balancePollIntervalMillis: Long = DEFAULT_BALANCE_POLL_INTERVAL_MILLIS,
    private val maxBalancePolls: Int = DEFAULT_MAX_BALANCE_POLLS,
    private val statusPollIntervalMillis: Long = DEFAULT_STATUS_POLL_INTERVAL_MILLIS,
    private val maxStatusFailures: Int = DEFAULT_MAX_STATUS_FAILURES,
    private val requoteCostToleranceBps: Int = DEFAULT_REQUOTE_COST_TOLERANCE_BPS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : OnrampZecDeliveryDriver {
    override fun deliver(
        orderId: String,
        recipient: Address,
        amount: Usdc6,
        resume: OnrampZecDeliveryCheckpoint?,
    ): Flow<OnrampZecDeliveryStatus> =
        flow {
            require(orderId.isNotBlank()) { "P2P order id is missing" }
            require(amount > Usdc6.ZERO) { "Delivery amount must be positive" }
            if (resume != null && Address.parse(resume.baseAccount) != recipient) {
                emit(failed(resume.phase, FundsLocation.RECIPIENT_MISMATCH, false))
                return@flow
            }
            if (resume != null && emitTerminal(resume, amount)) return@flow
            if (transfer.resolveAccount() != recipient) {
                emit(failed(OnrampZecDeliveryPhase.FUNDS_ON_BASE, FundsLocation.RECIPIENT_MISMATCH, false))
                return@flow
            }
            if (resume != null && resume.usdcMicros != amount.micros.toString()) {
                settlementDisagrees(orderId, recipient, amount, resume)
                return@flow
            }
            continueDelivery(orderId, recipient, amount, resume)
        }

    /**
     * The order settled for an amount the pinned quote was not taken for. Nothing may be deposited
     * against that quote. But an unstarted leg proves the money is still on Base, and re-quoting for
     * the settled amount is safe there, so the checkpoint is rewritten to the amount that actually
     * arrived — left naming the old one, every retry would rediscover the same disagreement.
     */
    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.settlementDisagrees(
        orderId: String,
        account: Address,
        amount: Usdc6,
        resume: OnrampZecDeliveryCheckpoint,
    ) {
        if (resume.transferStarted) {
            emit(failed(resume.phase, resume.fundsLocation, false))
            return
        }
        leaveOnBase(orderId, account, amount, resume.acceptedCostBps, resume.phase)
    }

    /**
     * An acknowledged outcome is replayed from the checkpoint, never re-derived from the provider:
     * re-polling a settled swap would let a network outage or an aged-out 1-Click record downgrade a
     * delivery the user already received.
     */
    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.emitTerminal(
        resume: OnrampZecDeliveryCheckpoint,
        amount: Usdc6,
    ): Boolean =
        when (resume.phase) {
            OnrampZecDeliveryPhase.DELIVERED -> {
                emit(
                    OnrampZecDeliveryStatus.Delivered(
                        inputUsdc = amount,
                        outputZec = resume.outputZec.orEmpty(),
                        baseTransactionHash = resume.baseTransactionHash,
                    ),
                )
                true
            }

            OnrampZecDeliveryPhase.REFUNDED_TO_BASE -> {
                emit(
                    OnrampZecDeliveryStatus.RefundedToBase(
                        inputUsdc = amount,
                        refundedUsdc = Usdc6(BigInteger(resume.refundedUsdcMicros.orEmpty())),
                        baseAccount = Address.parse(resume.baseAccount),
                    ),
                )
                true
            }

            else -> {
                false
            }
        }

    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.continueDelivery(
        orderId: String,
        account: Address,
        amount: Usdc6,
        resume: OnrampZecDeliveryCheckpoint?,
    ) {
        // A resumed leg would otherwise show nothing until its next network result, which for an
        // in-flight swap is minutes of blank screen.
        resume?.resumeStatus(amount)?.let { emit(it) }
        when (resume?.phase) {
            null,
            OnrampZecDeliveryPhase.FUNDS_ON_BASE,
            OnrampZecDeliveryPhase.QUOTING,
            -> {
                startFresh(orderId, account, amount, resume?.acceptedCostBps)
            }

            OnrampZecDeliveryPhase.QUOTE_READY -> {
                if ((resume.quoteDeadlineMillis ?: 0L) <= nowMillis() + ZEC_QUOTE_EXPIRY_MARGIN_MILLIS) {
                    startFresh(orderId, account, amount, resume.acceptedCostBps)
                } else {
                    submit(orderId, account, amount, resume)
                }
            }

            OnrampZecDeliveryPhase.TRANSFER_STARTING -> {
                reconcileAmbiguous(orderId, account, amount, resume)
            }

            OnrampZecDeliveryPhase.TRANSFER_SUBMITTED -> {
                awaitReceipt(orderId, account, amount, resume)
            }

            OnrampZecDeliveryPhase.AWAITING_ZEC -> {
                pollSwap(orderId, account, amount, resume)
            }

            OnrampZecDeliveryPhase.NEEDS_ATTENTION -> {
                when {
                    resume.baseTransactionHash != null -> pollSwap(orderId, account, amount, resume)
                    resume.userOperationHash != null -> awaitReceipt(orderId, account, amount, resume)
                    else -> reconcileAmbiguous(orderId, account, amount, resume)
                }
            }

            OnrampZecDeliveryPhase.DELIVERED,
            OnrampZecDeliveryPhase.REFUNDED_TO_BASE,
            -> {
                Unit
            }
        }
    }

    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.startFresh(
        orderId: String,
        account: Address,
        amount: Usdc6,
        acceptedCostBps: Int?,
    ) {
        emit(OnrampZecDeliveryStatus.Preparing(amount))
        val quote = quoteOrLeaveOnBase(orderId, account, amount, acceptedCostBps) ?: return
        val checkpoint =
            OnrampZecDeliveryCheckpoint(
                phase = OnrampZecDeliveryPhase.QUOTE_READY,
                usdcMicros = amount.micros.toString(),
                baseAccount = account.checksumHex,
                zcashRecipient = quote.zcashRecipient,
                depositAddress = quote.depositAddress.checksumHex,
                quoteDeadlineMillis = quote.deadlineMillis,
                acceptedCostBps = acceptedCostBps,
            )
        checkpoints.save(orderId, checkpoint)
        submit(orderId, account, amount, checkpoint)
    }

    /** A quote that may be deposited against, or null with the USDC left recoverable on Base. */
    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.quoteOrLeaveOnBase(
        orderId: String,
        account: Address,
        amount: Usdc6,
        acceptedCostBps: Int?,
    ): ValidatedZecSwapQuote? {
        if (!awaitBalance(account, amount)) {
            leaveOnBase(orderId, account, amount, acceptedCostBps, OnrampZecDeliveryPhase.FUNDS_ON_BASE)
            return null
        }
        checkpoints.save(orderId, baseCheckpoint(OnrampZecDeliveryPhase.QUOTING, account, amount, acceptedCostBps))
        val quote = attempt("quote") { swap.quote(account, amount) }
        val rejected = quote == null || quote.exceeds(acceptedCostBps)
        if (rejected) {
            leaveOnBase(orderId, account, amount, acceptedCostBps, OnrampZecDeliveryPhase.QUOTING)
        }
        return quote.takeUnless { rejected }
    }

    /**
     * The money is provably still in the Base account: record that and say so retryably. Every
     * caller has either not started a transfer or watched one revert.
     */
    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.leaveOnBase(
        orderId: String,
        account: Address,
        amount: Usdc6,
        acceptedCostBps: Int?,
        stage: OnrampZecDeliveryPhase,
    ) {
        checkpoints.save(
            orderId,
            baseCheckpoint(OnrampZecDeliveryPhase.FUNDS_ON_BASE, account, amount, acceptedCostBps),
        )
        emit(failed(stage, FundsLocation.BASE_ACCOUNT, true))
    }

    /**
     * A settlement-time re-quote is one the user never saw. It may not spend more of the order on
     * the route than the estimate they accepted, give or take [requoteCostToleranceBps].
     */
    private fun ValidatedZecSwapQuote.exceeds(acceptedCostBps: Int?): Boolean {
        if (acceptedCostBps == null) return false
        val exceeds = costBasisPoints > acceptedCostBps + requoteCostToleranceBps
        if (exceeds) {
            Twig.warn {
                "NearOnrampZecDeliveryDriver: re-quote costs ${costBasisPoints}bps against an " +
                    "accepted ${acceptedCostBps}bps; not depositing"
            }
        }
        return exceeds
    }

    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.submit(
        orderId: String,
        account: Address,
        amount: Usdc6,
        checkpoint: OnrampZecDeliveryCheckpoint,
    ) {
        val starting = checkpoint.copy(phase = OnrampZecDeliveryPhase.TRANSFER_STARTING, transferStarted = true)
        checkpoints.save(orderId, starting)
        emit(OnrampZecDeliveryStatus.Submitting(amount))
        val hash =
            attempt("transfer submission") {
                transfer.submit(account, Address.parse(starting.depositAddress.orEmpty()), amount)
            } ?: run {
                checkpoints.save(orderId, starting.copy(phase = OnrampZecDeliveryPhase.NEEDS_ATTENTION))
                emit(failed(OnrampZecDeliveryPhase.TRANSFER_STARTING, FundsLocation.TRANSFER_AMBIGUOUS, false))
                return
            }
        val submitted =
            starting.copy(
                phase = OnrampZecDeliveryPhase.TRANSFER_SUBMITTED,
                userOperationHash = hash,
            )
        // The UserOperation is already broadcast; losing this write to cancellation would strand it
        // as an unattributable ambiguous transfer.
        withContext(NonCancellable) {
            checkpoints.save(orderId, submitted)
        }
        awaitReceipt(orderId, account, amount, submitted)
    }

    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.awaitReceipt(
        orderId: String,
        account: Address,
        amount: Usdc6,
        checkpoint: OnrampZecDeliveryCheckpoint,
    ) {
        val receipt =
            attempt("transfer receipt") {
                transfer.awaitReceipt(account, checkpoint.userOperationHash.orEmpty())
            } ?: run {
                checkpoints.save(orderId, checkpoint.copy(phase = OnrampZecDeliveryPhase.NEEDS_ATTENTION))
                emit(failed(OnrampZecDeliveryPhase.TRANSFER_SUBMITTED, FundsLocation.TRANSFER_AMBIGUOUS, false))
                return
            }
        if (!receipt.success) {
            leaveOnBase(
                orderId,
                account,
                amount,
                checkpoint.acceptedCostBps,
                OnrampZecDeliveryPhase.TRANSFER_SUBMITTED,
            )
            return
        }
        val awaiting =
            checkpoint.copy(
                phase = OnrampZecDeliveryPhase.AWAITING_ZEC,
                baseTransactionHash = receipt.transactionHash,
            )
        checkpoints.save(orderId, awaiting)
        // Best-effort acceleration only: 1-Click detects the deposit on its own, and a failure here
        // must never be read as a reason to send the USDC again.
        attempt("deposit notification") {
            swap.notifyDeposit(receipt.transactionHash, Address.parse(awaiting.depositAddress.orEmpty()))
        }
        emit(OnrampZecDeliveryStatus.AwaitingZec(amount))
        pollSwap(orderId, account, amount, awaiting)
    }

    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.pollSwap(
        orderId: String,
        account: Address,
        amount: Usdc6,
        checkpoint: OnrampZecDeliveryCheckpoint,
    ) {
        var failures = 0
        while (true) {
            val result = attempt("swap status") { swap.status(checkpoint) }
            if (result == null) {
                failures++
                if (failures >= maxStatusFailures) {
                    checkpoints.save(orderId, checkpoint.copy(phase = OnrampZecDeliveryPhase.NEEDS_ATTENTION))
                    emit(failed(OnrampZecDeliveryPhase.AWAITING_ZEC, FundsLocation.NEAR_INTENT, true))
                    return
                }
                delay(statusPollIntervalMillis shl (failures - 1))
                continue
            }
            failures = 0
            if (settle(orderId, account, amount, checkpoint, result)) return
            delay(statusPollIntervalMillis)
        }
    }

    /** Records and reports a provider verdict; false means the swap is still running. */
    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.settle(
        orderId: String,
        account: Address,
        amount: Usdc6,
        checkpoint: OnrampZecDeliveryCheckpoint,
        result: OnrampZecSwapResult,
    ): Boolean =
        when (result.status) {
            SwapStatus.SUCCESS -> {
                checkpoints.save(
                    orderId,
                    checkpoint.copy(phase = OnrampZecDeliveryPhase.DELIVERED, outputZec = result.outputZec),
                )
                emit(OnrampZecDeliveryStatus.Delivered(amount, result.outputZec, checkpoint.baseTransactionHash))
                true
            }

            SwapStatus.REFUNDED -> {
                checkpoints.save(
                    orderId,
                    checkpoint.copy(
                        phase = OnrampZecDeliveryPhase.REFUNDED_TO_BASE,
                        refundedUsdcMicros = requireNotNull(result.refundedUsdc).micros.toString(),
                    ),
                )
                emit(OnrampZecDeliveryStatus.RefundedToBase(amount, requireNotNull(result.refundedUsdc), account))
                true
            }

            // None of these prove where the money is, so the recovery checkpoint is kept and the
            // funds are still described as the intent's.
            SwapStatus.FAILED,
            SwapStatus.EXPIRED,
            SwapStatus.INCOMPLETE_DEPOSIT,
            -> {
                checkpoints.save(orderId, checkpoint.copy(phase = OnrampZecDeliveryPhase.NEEDS_ATTENTION))
                emit(failed(OnrampZecDeliveryPhase.AWAITING_ZEC, FundsLocation.NEAR_INTENT, false))
                true
            }

            SwapStatus.PENDING,
            SwapStatus.PROCESSING,
            -> {
                false
            }
        }

    private suspend fun FlowCollector<OnrampZecDeliveryStatus>.reconcileAmbiguous(
        orderId: String,
        account: Address,
        amount: Usdc6,
        checkpoint: OnrampZecDeliveryCheckpoint,
    ) {
        val result = attempt("ambiguous transfer reconciliation") { swap.status(checkpoint) }
        if (result?.status == SwapStatus.SUCCESS || result?.status == SwapStatus.REFUNDED) {
            pollSwap(orderId, account, amount, checkpoint)
            return
        }
        checkpoints.save(orderId, checkpoint.copy(phase = OnrampZecDeliveryPhase.NEEDS_ATTENTION))
        emit(failed(OnrampZecDeliveryPhase.TRANSFER_STARTING, FundsLocation.TRANSFER_AMBIGUOUS, false))
    }

    private suspend fun awaitBalance(account: Address, amount: Usdc6): Boolean {
        repeat(maxBalancePolls) { poll ->
            val balance = attempt("Base balance read") { transfer.balance(account) }
            if (balance != null && balance >= amount) return true
            if (poll + 1 < maxBalancePolls) delay(balancePollIntervalMillis)
        }
        return false
    }

    /**
     * Single catch point for the leg's network calls. Sanitized by construction: [step] is a fixed
     * label, so no address, amount, or provider body reaches the log. Cancellation still propagates.
     */
    private suspend fun <T> attempt(step: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable
        ) {
            Twig.warn { "NearOnrampZecDeliveryDriver: $step failed (${e::class.simpleName})" }
            null
        }

    private fun baseCheckpoint(
        phase: OnrampZecDeliveryPhase,
        account: Address,
        amount: Usdc6,
        acceptedCostBps: Int?,
    ) = OnrampZecDeliveryCheckpoint(
        phase = phase,
        usdcMicros = amount.micros.toString(),
        baseAccount = account.checksumHex,
        acceptedCostBps = acceptedCostBps,
    )

    private fun failed(stage: OnrampZecDeliveryPhase, location: FundsLocation, retryable: Boolean) =
        OnrampZecDeliveryStatus.Failed(stage, location, retryable)

    private fun OnrampZecDeliveryCheckpoint.resumeStatus(amount: Usdc6): OnrampZecDeliveryStatus? =
        when (phase) {
            OnrampZecDeliveryPhase.QUOTE_READY,
            OnrampZecDeliveryPhase.TRANSFER_STARTING,
            OnrampZecDeliveryPhase.TRANSFER_SUBMITTED,
            -> {
                OnrampZecDeliveryStatus.Submitting(amount)
            }

            OnrampZecDeliveryPhase.AWAITING_ZEC -> {
                OnrampZecDeliveryStatus.AwaitingZec(amount)
            }

            OnrampZecDeliveryPhase.NEEDS_ATTENTION -> {
                if (baseTransactionHash != null) {
                    OnrampZecDeliveryStatus.AwaitingZec(amount)
                } else {
                    OnrampZecDeliveryStatus.Submitting(amount)
                }
            }

            // startFresh emits Preparing itself; DELIVERED and REFUNDED_TO_BASE never reach here.
            OnrampZecDeliveryPhase.FUNDS_ON_BASE,
            OnrampZecDeliveryPhase.QUOTING,
            OnrampZecDeliveryPhase.DELIVERED,
            OnrampZecDeliveryPhase.REFUNDED_TO_BASE,
            -> {
                null
            }
        }

    private companion object {
        const val DEFAULT_BALANCE_POLL_INTERVAL_MILLIS = 2_000L
        const val DEFAULT_MAX_BALANCE_POLLS = 5
        const val DEFAULT_STATUS_POLL_INTERVAL_MILLIS = 5_000L
        const val DEFAULT_MAX_STATUS_FAILURES = 5

        // Headroom over the accepted cost for a settlement-time re-quote. Market moves carry input
        // and output together, so this only has to absorb route and network-fee drift.
        const val DEFAULT_REQUOTE_COST_TOLERANCE_BPS = 200
    }
}

internal class FakeOnrampZecDeliveryDriver : OnrampZecDeliveryDriver {
    override fun deliver(
        orderId: String,
        recipient: Address,
        amount: Usdc6,
        resume: OnrampZecDeliveryCheckpoint?,
    ): Flow<OnrampZecDeliveryStatus> =
        flow {
            emit(OnrampZecDeliveryStatus.Preparing(amount))
            emit(OnrampZecDeliveryStatus.Submitting(amount))
            emit(OnrampZecDeliveryStatus.AwaitingZec(amount))
            emit(OnrampZecDeliveryStatus.Delivered(amount, FAKE_OUTPUT_ZEC, null))
        }

    private companion object {
        const val FAKE_OUTPUT_ZEC = "0.01"
    }
}

internal class NoRouteOnrampZecDeliveryDriver : OnrampZecDeliveryDriver {
    override fun deliver(
        orderId: String,
        recipient: Address,
        amount: Usdc6,
        resume: OnrampZecDeliveryCheckpoint?,
    ): Flow<OnrampZecDeliveryStatus> =
        flowOf(
            OnrampZecDeliveryStatus.Failed(
                stage = OnrampZecDeliveryPhase.FUNDS_ON_BASE,
                fundsLocation = FundsLocation.BASE_ACCOUNT,
                retryable = false,
            ),
        )
}
