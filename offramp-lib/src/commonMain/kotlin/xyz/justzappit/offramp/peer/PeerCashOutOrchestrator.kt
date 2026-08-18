// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.signer.TxSubmitter
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBigInteger
import xyz.justzappit.offramp.funding.OfframpTopUp
import xyz.justzappit.offramp.orchestrator.KnownReverts
import xyz.justzappit.offramp.p2p.Erc20Calls
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getUsdcBalance

interface PeerCashOutOrchestrator {
    /** Runs a fresh order through to a live deposit, then polls it. */
    fun createOrder(request: PeerCashOutRequest): Flow<PeerCashOutStatus>

    /** Picks a partial run back up. Never re-broadcasts anything already sent. */
    fun resume(checkpoint: PeerCashOutCheckpoint): Flow<PeerCashOutStatus>

    /** Everything the waiting screen needs, from the deposit id alone. */
    fun observeOrder(id: PeerDepositId): Flow<PeerCashOutStatus>

    fun withdraw(id: PeerDepositId, amount: Usdc6): Flow<PeerCashOutStatus>

    fun setAcceptingIntents(id: PeerDepositId, accepting: Boolean): Flow<PeerCashOutStatus>

    /** Every order this seed has ever opened and not closed. No local list, by design. */
    suspend fun activeOrders(): List<PeerOrderSnapshot>

    /** Closed ones included, which is what a checkpoint written before the deposit existed needs. */
    suspend fun allOrders(): List<PeerOrderSnapshot>
}

@Suppress("TooManyFunctions")
class PeerCashOutOrchestratorImpl(
    private val network: PeerNetworkConfig,
    private val account: Address,
    private val txSubmitter: TxSubmitter,
    private val rpcClient: BaseRpcClient,
    private val curatorClient: PeerCuratorClient,
    private val indexerClient: PeerIndexerClient,
    private val topUp: OfframpTopUp,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) : PeerCashOutOrchestrator {
    override fun createOrder(request: PeerCashOutRequest): Flow<PeerCashOutStatus> =
        peerFlow(PeerCashOutStep.INITIALIZATION) { cursor ->
            emit(PeerCashOutStatus.Idle)

            cursor.step = PeerCashOutStep.VALIDATING_PAYEE
            val payeeHash = resolvePayeeHash(request)

            cursor.step = PeerCashOutStep.FUNDING
            fund(request.amount)

            cursor.step = PeerCashOutStep.APPROVING_USDC
            approve(request.amount)

            cursor.step = PeerCashOutStep.CREATING_DEPOSIT
            val depositId =
                createDeposit(
                    platform = request.platform,
                    currencies = request.currencies,
                    amount = request.amount,
                    payeeHash = payeeHash,
                )

            cursor.depositId = depositId
            pollOrder(depositId)
        }

    override fun resume(checkpoint: PeerCashOutCheckpoint): Flow<PeerCashOutStatus> =
        peerFlow(PeerCashOutStep.INITIALIZATION, checkpoint.depositId) { cursor ->
            emit(PeerCashOutStatus.Idle)
            when (val action = checkpoint.resumeAction) {
                is PeerResumeAction.ReadOrder -> {
                    cursor.step = PeerCashOutStep.AWAITING_BUYER
                    pollOrder(action.depositId)
                }

                is PeerResumeAction.ResolveSubmittedDeposit -> {
                    cursor.step = PeerCashOutStep.CREATING_DEPOSIT
                    pollOrder(resolveSubmittedDeposit(action, checkpoint))
                }

                PeerResumeAction.ReconcileSubmission -> {
                    cursor.step = PeerCashOutStep.CREATING_DEPOSIT
                    pollOrder(reconcileSubmission(checkpoint))
                }

                is PeerResumeAction.ResumeBridge -> {
                    cursor.step = PeerCashOutStep.FUNDING
                    resumeFrom(checkpoint, action.depositAddress)
                }

                PeerResumeAction.FreshStart -> {
                    cursor.step = PeerCashOutStep.FUNDING
                    resumeFrom(checkpoint, null)
                }
            }
        }

    override fun observeOrder(id: PeerDepositId): Flow<PeerCashOutStatus> =
        peerFlow(PeerCashOutStep.AWAITING_BUYER, id) { pollOrder(id) }

    override fun withdraw(id: PeerDepositId, amount: Usdc6): Flow<PeerCashOutStatus> =
        peerFlow(PeerCashOutStep.WITHDRAWING, id) {
            unblockWithdrawal(id, amount)
            emit(PeerCashOutStatus.Withdrawing(depositId = id, amount = amount))
            val txHash = submit(PeerEscrowCalls.removeFundsCalldata(id.onchainValue, amount))
            emit(PeerCashOutStatus.Withdrawing(depositId = id, amount = amount, txHash = txHash))
            requireSuccess(txSubmitter.awaitReceipt(txHash))
            emit(PeerCashOutStatus.Withdrawn(depositId = id, amount = amount, txHash = txHash))
        }

    /**
     * Ends on one fresh read rather than polling. The waiting screen has its own [observeOrder]
     * poll, and a second endless one here never completes, which holds the screen busy and blocks
     * every later action.
     */
    override fun setAcceptingIntents(id: PeerDepositId, accepting: Boolean): Flow<PeerCashOutStatus> =
        peerFlow(PeerCashOutStep.AWAITING_BUYER, id) {
            val txHash = submit(PeerEscrowCalls.setAcceptingIntentsCalldata(id.onchainValue, accepting))
            runPeerCatching { requireSuccess(txSubmitter.awaitReceipt(txHash)) }
                .onFailure { if (!it.isBenignEscrowRevert()) throw it }
            readOrder(id)?.let { emit(PeerCashOutStatus.OrderLive(it)) }
        }

    /**
     * Every entry point converts any failure into a [PeerCashOutStatus.Failed] carrying the step it
     * reached, so the single broad catch lives here rather than being repeated per method. The
     * cursor is what lets the body report how far it got.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun peerFlow(
        initialStep: PeerCashOutStep,
        initialDepositId: PeerDepositId? = null,
        body: suspend FlowCollector<PeerCashOutStatus>.(StepCursor) -> Unit,
    ): Flow<PeerCashOutStatus> =
        flow {
            val cursor = StepCursor(step = initialStep, depositId = initialDepositId)
            try {
                body(cursor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(failure(e, cursor.step, cursor.depositId))
            }
        }

    /** An expired intent can hold the balance; pruning it first is what makes the withdrawal land. */
    private suspend fun unblockWithdrawal(id: PeerDepositId, amount: Usdc6) {
        val snapshot = readOrder(id) ?: throw PeerErrorCode.ORDER_NOT_FOUND.asException()
        withdrawalBlocker(snapshot, amount)?.let { throw it.asException() }
        // Unconditional: an expired intent holds funds whether or not this particular withdrawal
        // needs them, and leaving it in place is what strands a balance nobody can reach.
        if (snapshot.hasExpiredIntentHoldingFunds) {
            prune(id)
        }
    }

    private fun withdrawalBlocker(snapshot: PeerOrderSnapshot, amount: Usdc6): PeerErrorCode? =
        when {
            // A buyer signalling between render and tap holds the balance without emptying the
            // order, and "nothing to withdraw" reads as a loss on funds that come back unpaid.
            snapshot.withdrawableAfterPrune <= Usdc6.ZERO && snapshot.liveIntents.isNotEmpty() -> {
                PeerErrorCode.ACTIVE_INTENT_BLOCKS_WITHDRAWAL
            }

            snapshot.withdrawableAfterPrune <= Usdc6.ZERO -> {
                PeerErrorCode.NOTHING_TO_WITHDRAW
            }

            amount > snapshot.withdrawableAfterPrune -> {
                PeerErrorCode.INSUFFICIENT_AVAILABLE_FUNDS
            }

            else -> {
                null
            }
        }

    private class StepCursor(
        var step: PeerCashOutStep,
        var depositId: PeerDepositId?,
    )

    override suspend fun activeOrders(): List<PeerOrderSnapshot> = indexerClient.activeOrdersFor(account)

    override suspend fun allOrders(): List<PeerOrderSnapshot> = indexerClient.allOrdersFor(account)

    private suspend fun FlowCollector<PeerCashOutStatus>.resumeFrom(
        checkpoint: PeerCashOutCheckpoint,
        bridgeHandle: String?,
    ) {
        fund(checkpoint.amount, bridgeHandle)
        approve(checkpoint.amount)
        pollOrder(
            createDeposit(
                platform = checkpoint.platform,
                currencies = checkpoint.currencies,
                amount = checkpoint.amount,
                payeeHash = checkpoint.payeeHash,
            ),
        )
    }

    private suspend fun FlowCollector<PeerCashOutStatus>.resolvePayeeHash(
        request: PeerCashOutRequest,
    ): PayeeHash {
        val platform = request.platform
        emit(PeerCashOutStatus.ValidatingPayee(platform))
        val hash =
            request.cachedPayeeHash ?: run {
                val handle = request.handle ?: throw PeerErrorCode.PAYEE_REGISTRATION_FAILED.asException()
                if (!curatorClient.validatePayee(platform, handle)) {
                    throw PeerErrorCode.PAYEE_REGISTRATION_FAILED.asException()
                }
                curatorClient.registerPayee(platform, handle)
            }
        emit(PeerCashOutStatus.ValidatingPayee(platform, payeeHash = hash))
        return hash
    }

    /**
     * Verifies rather than acquires. A cash-out spends Base USDC the user already has: the amount
     * screen caps the input at the spendable balance, so a shortfall here is a race or a stale read,
     * not a cue to move their ZEC without asking. Topping up is its own screen with its own progress.
     *
     * [resumeHandle] is the one exception. A bridge that a previous build already started still has
     * ZEC in flight, and abandoning it would strand the funds.
     */
    private suspend fun FlowCollector<PeerCashOutStatus>.fund(amount: Usdc6, resumeHandle: String? = null) {
        if (resumeHandle != null) {
            finishBridge(amount, resumeHandle)
            return
        }
        val balance = rpcClient.getUsdcBalance(network.usdcAddress, account)
        if (balance < amount) throw PeerErrorCode.INSUFFICIENT_TOKEN_BALANCE.asException()
        emit(PeerCashOutStatus.FundedFromBase(amount = amount, baseBalance = balance))
    }

    private suspend fun FlowCollector<PeerCashOutStatus>.finishBridge(amount: Usdc6, resumeHandle: String) {
        val balance = rpcClient.getUsdcBalance(network.usdcAddress, account)
        val shortfall = if (balance >= amount) Usdc6.ZERO else amount - balance
        emit(PeerCashOutStatus.BridgingFunds(amount = shortfall, depositAddress = resumeHandle))
        runPeerCatching {
            topUp.bridge(
                account = account,
                usdc = shortfall,
                resumeHandle = resumeHandle,
                onBridgeStarted = { address ->
                    emit(PeerCashOutStatus.BridgingFunds(amount = shortfall, depositAddress = address))
                },
            )
        }.getOrElse { throw PeerErrorCode.FUNDING_BRIDGE_FAILED.asException(cause = it) }

        val funded = rpcClient.getUsdcBalance(network.usdcAddress, account)
        if (funded < amount) throw PeerErrorCode.INSUFFICIENT_TOKEN_BALANCE.asException()
        emit(PeerCashOutStatus.FundedFromBase(amount = amount, baseBalance = funded))
    }

    private suspend fun FlowCollector<PeerCashOutStatus>.approve(amount: Usdc6) {
        val txHash = submit(Erc20Calls.approveCalldata(network.escrowAddress, amount), to = network.usdcAddress)
        emit(PeerCashOutStatus.ApprovingUsdc(txHash = txHash, amount = amount))
        requireSuccess(txSubmitter.awaitReceipt(txHash))
    }

    private suspend fun FlowCollector<PeerCashOutStatus>.createDeposit(
        platform: PeerPlatform,
        currencies: List<PeerCurrency>,
        amount: Usdc6,
        payeeHash: PayeeHash,
    ): PeerDepositId {
        val params =
            PeerDepositParams(
                token = network.usdcAddress,
                amount = amount,
                platform = platform,
                payeeHash = payeeHash,
                currencies = currencies,
                gatingService = network.gatingServiceAddress,
                oracleAdapter = network.oracleAdapterAddress,
                intentAmountMin = PeerDepositParams.defaultIntentAmountMin(amount),
            )
        // Read the head before broadcasting so a lost receipt still has a lower bound to scan from.
        // The collector persists this before the send returns, which is what makes a submission
        // whose hash never came back recoverable instead of repeatable — so a failed read stops the
        // send rather than escrowing against an anchor that does not exist. Decimal, because that is
        // what the indexer reports block numbers in.
        val fromBlock =
            runPeerCatching { hexToBigInteger(rpcClient.ethGetBlockByNumber().number).toString() }
                .getOrElse { throw PeerErrorCode.TRANSACTION_FAILED.asException(cause = it) }
        emit(PeerCashOutStatus.CreatingDeposit(amount = amount, fromBlockNumber = fromBlock))

        val txHash =
            runPeerCatching { submit(PeerEscrowCalls.createDepositCalldata(params)) }
                .getOrElse { throw it.asSubmissionFailure() }
        emit(PeerCashOutStatus.CreatingDeposit(amount = amount, fromBlockNumber = fromBlock, txHash = txHash))

        return resolveDepositFromReceipt(txHash)
    }

    private suspend fun resolveDepositFromReceipt(txHash: TxHash): PeerDepositId {
        val receipt =
            runPeerCatching { txSubmitter.awaitReceipt(txHash) }
                .getOrElse {
                    throw PeerErrorCode.TRANSACTION_STATUS_UNKNOWN.asException(
                        recovery = PeerRecovery.InspectBaseTransaction(txHash, OPERATION_CREATE_DEPOSIT),
                        cause = it,
                    )
                }
        requireSuccess(receipt)
        return PeerDepositReceipt.depositIdFrom(receipt, network.escrowAddress)
            ?: throw PeerErrorCode.DEPOSIT_RESOLUTION_FAILED.asException(
                recovery = PeerRecovery.InspectBaseTransaction(txHash, OPERATION_CREATE_DEPOSIT),
            )
    }

    /**
     * The hash-only resume path. Resolves what was already sent; never sends it again, because a
     * second `createDeposit` escrows a second lot of USDC.
     */
    private suspend fun resolveSubmittedDeposit(
        action: PeerResumeAction.ResolveSubmittedDeposit,
        checkpoint: PeerCashOutCheckpoint,
    ): PeerDepositId {
        val receipt = runPeerCatching { txSubmitter.awaitReceipt(action.txHash) }.getOrNull()
        if (receipt != null) {
            // A known revert is the one case where nothing was escrowed, so it is a plain failure
            // rather than a hunt for an order that does not exist.
            if (!receipt.success) throw PeerErrorCode.TRANSACTION_FAILED.asException()
            PeerDepositReceipt.depositIdFrom(receipt, network.escrowAddress)?.let { return it }
        }
        val orders = ownDeposits()
        val match =
            minedHash(receipt)?.let { mined -> orders.firstOrNull { it.wasCreatedBy(mined) } }
                ?: orders.firstOrNull { it.couldHaveBeenOpenedBy(checkpoint, checkpoint.blockFloor) }
        return match?.id
            ?: throw PeerErrorCode.DEPOSIT_RESOLUTION_FAILED.asException(
                recovery = PeerRecovery.InspectBaseTransaction(action.txHash, OPERATION_CREATE_DEPOSIT),
            )
    }

    /**
     * The checkpoint's hash is a userOp hash; the indexer records the bundle transaction that
     * carried it. The two never compare equal, so the receipt is where they meet — without it the
     * exact match cannot fire and every recovery falls through to the fuzzy one.
     */
    private fun minedHash(receipt: TransactionReceipt?): TxHash? =
        receipt?.let { runCatching { TxHash.fromHex(it.transactionHash) }.getOrNull() }

    /**
     * The submission returned no hash, which does not mean it failed to broadcast. The order is
     * looked up from the block read before sending; if it is not there that is a hard stop rather
     * than a retry, because the alternative is escrowing the amount a second time.
     */
    private suspend fun reconcileSubmission(checkpoint: PeerCashOutCheckpoint): PeerDepositId =
        ownDeposits()
            .firstOrNull { it.couldHaveBeenOpenedBy(checkpoint, checkpoint.blockFloor) }
            ?.id
            ?: throw PeerErrorCode.TRANSACTION_SUBMISSION_UNKNOWN.asException(
                recovery = PeerRecovery.InspectDepositor(account),
            )

    // A read failure here must not read as "the deposit is not there": that is the one conclusion
    // that would justify sending again.
    private suspend fun ownDeposits(): List<PeerOrderSnapshot> =
        runPeerCatching { allOrders() }
            .getOrElse { throw PeerErrorCode.INDEXER_UNAVAILABLE.asException(cause = it) }

    private suspend fun prune(id: PeerDepositId) {
        val txHash = submit(PeerEscrowCalls.pruneExpiredIntentsCalldata(id.onchainValue))
        runPeerCatching { requireSuccess(txSubmitter.awaitReceipt(txHash)) }
            .onFailure { if (!it.isBenignEscrowRevert()) throw it }
    }

    /**
     * Polls while collected. A read failure never becomes an order failure: the last known state is
     * kept and the poll simply tries again, because only a reverted transaction is a failure.
     */
    private suspend fun FlowCollector<PeerCashOutStatus>.pollOrder(id: PeerDepositId) {
        var lastEmitted: PeerOrderSnapshot? = null
        while (true) {
            val snapshot = runPeerCatching { readOrder(id) }.getOrNull()
            if (snapshot != null && snapshot != lastEmitted) {
                lastEmitted = snapshot
                emit(PeerCashOutStatus.OrderLive(snapshot))
            }
            if (snapshot != null && snapshot.isTerminal) return
            delay(pollIntervalMillis)
        }
    }

    private suspend fun readOrder(id: PeerDepositId): PeerOrderSnapshot? = indexerClient.order(id)

    private suspend fun submit(data: ByteArray, to: Address = network.escrowAddress): TxHash =
        txSubmitter.sendTransaction(to = to, data = data)

    private fun requireSuccess(receipt: TransactionReceipt) {
        if (!receipt.success) {
            throw PeerErrorCode.TRANSACTION_FAILED.asException(
                recovery = PeerRecovery.InspectBaseTransaction(TxHash.fromHex(receipt.transactionHash), OPERATION_SEND),
            )
        }
    }

    private fun Throwable.asSubmissionFailure(): Throwable =
        if (this is PeerException) {
            this
        } else {
            PeerErrorCode.TRANSACTION_SUBMISSION_UNKNOWN.asException(
                recovery = PeerRecovery.InspectDepositor(account),
                cause = this,
            )
        }

    private fun Throwable.isBenignEscrowRevert(): Boolean =
        EscrowRevert.fromSelector(selectorOf(this))?.isBenign == true

    private fun failure(
        error: Throwable,
        step: PeerCashOutStep,
        depositId: PeerDepositId?,
    ): PeerCashOutStatus.Failed {
        val peerError =
            when (error) {
                is PeerException -> {
                    error.error
                }

                else -> {
                    val selector = selectorOf(error)
                    PeerError(
                        code = PeerErrorCode.TRANSACTION_FAILED,
                        revertSelector = selector,
                        escrowRevert = EscrowRevert.fromSelector(selector),
                        solidityErrorString = (error as? RpcException.ExecutionReverted)?.solidityErrorString,
                        cause = error,
                    )
                }
            }
        return PeerCashOutStatus.Failed(step = step, error = peerError, depositId = depositId)
    }

    // ERC-4337 reverts arrive as an opaque bundler message rather than structured revert data, so
    // the selector is recovered from the text the same way the p2p.me path does it.
    private fun selectorOf(error: Throwable) =
        when (error) {
            is RpcException.ExecutionReverted -> error.selector
            is RpcException.Unknown -> KnownReverts.selectorFromMessage(error.errorMessage ?: error.raw)
            else -> null
        }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 5_000L
        const val OPERATION_CREATE_DEPOSIT = "createDeposit"
        const val OPERATION_SEND = "send"
    }
}
