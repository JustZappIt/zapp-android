// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address

@Suppress("TooManyFunctions")
class ReverseSwapDriver(
    private val deployment: ReverseDeployment,
    private val api: ReverseSwapApi,
    private val chain: ReverseSwapChain,
    private val keys: AtomicSwapKeys,
    private val reverseKeys: ReverseSwapKeys,
    private val zcash: ReverseSwapZcash,
    private val funding: ReverseSwapFunding,
    private val indices: AtomicSwapStore,
    private val store: ReverseSwapStore,
) {
    private val lock = Mutex()
    private val verifier = ReverseSwapVerifier(deployment, chain, keys)
    private val refunds = ReverseSwapRefunds(api, chain, keys, reverseKeys, store, verifier)

    suspend fun quote(units: Int): ReverseSwapRecord =
        lock.withLock {
            require(units > 0)
            check(store.active()?.underWay != true && indices.active()?.finished != false)
            verifier.verifyInfo(api.info())
            val index = indices.takeIndex()
            val user = keys.authAddress(index).lowercaseHex
            val note = keys.payoutNote(index)
            val quote = api.quote(units, user, note.commitment.hex())
            ReverseSwapVerifier.verifyQuote(quote, deployment, user, note.commitment)
            check(decimalUnits(quote.terms.amount) == bigIntegerValueOf(units.toLong()))
            val acceptance =
                keys.accept(
                    index,
                    deployment.chainId,
                    Address.parse(deployment.contract),
                    fixedHex(quote.terms.quoteId, SWAP_WORD_BYTES),
                    fixedHex(quote.terms.makerShare, SWAP_SHARE_BYTES),
                    fixedHex(quote.terms.makerProof, SWAP_SHARE_BYTES),
                )
            val id =
                AtomicSwapChain
                    .swapId(
                        Address.parse(user),
                        fixedHex(quote.terms.makerShare, SWAP_SHARE_BYTES)
                    ).hex()
            val now = chain.read(id).now
            check(now < quote.terms.expiresAt && now < quote.fundingDeadline)
            check(quote.readyDeadline > now + MIN_TIME_TO_READY && quote.refundAfter <= now + MAX_READY_WAIT)
            val record =
                ReverseSwapRecord(
                    index,
                    deployment,
                    quote,
                    id,
                    acceptance.userShare.hex(),
                    ReverseAcceptance(
                        acceptance.userShare.hex(),
                        acceptance.userProof.hex(),
                        acceptance.viewingKeys.hex()
                    ),
                    birthday = zcash.height(),
                    phase = ReversePhase.QUOTED,
                    cost = funding.cost(quote.terms.amount),
                )
            store.save(record)
            record
        }

    suspend fun review(index: Int) =
        lock.withLock {
            val record = current(index)
            check(record.phase == ReversePhase.QUOTED && indices.active()?.finished != false)
            val now = verifier.state(record).now
            check(now < record.quote.terms.expiresAt && now < record.quote.fundingDeadline)
            store.save(record.copy(phase = ReversePhase.ACCEPTING))
        }

    suspend fun advance(): ReverseSwapRecord? =
        lock.withLock {
            val record = store.active() ?: return@withLock null
            if (!record.underWay) return@withLock record
            advanceLocked(record)
            store.active()
        }

    /** Called only following the first foreground authorization. */
    suspend fun fund(index: Int) =
        lock.withLock {
            var record = current(index)
            check(record.underWay && !record.cancelRequested)
            if (record.funding != null) {
                advanceLocked(record)
                return@withLock
            }
            if (record.account == null) record = acceptAndImport(record)
            val observed = verifier.state(record)
            check(observed.swap == null)
            check(observed.now < record.quote.fundingDeadline)
            check(record.quote.readyDeadline > observed.now + MIN_TIME_TO_READY)
            val transaction = funding.prepare(record, reverseKeys.signOpen(record))
            check(transaction.cost == record.cost) { "funding fees changed; review a fresh quote" }
            record = record.copy(funding = transaction, phase = ReversePhase.SENDING_USDC)
            store.save(record)
            funding.submit(transaction)
        }

    /** Called only following the second foreground authorization, even after a restart. */
    suspend fun ready(index: Int) =
        lock.withLock {
            var record = current(index)
            check(record.underWay && !record.cancelRequested)
            if (record.ready != null) {
                advanceLocked(record)
                return@withLock
            }
            check(zcash.spendable(record) >= record.quote.terms.depositZat)
            val estimate = zcash.estimateReceive(record)
            check(estimate.availableZat >= record.quote.terms.depositZat)
            val observed = verifier.state(record)
            val escrow = checkNotNull(observed.swap)
            check(escrow.stage == SwapStage.OPEN && escrow.refundLockUntil == 0L)
            check(observed.now + SIGNATURE_TTL < record.quote.readyDeadline)
            val deadline = observed.now + SIGNATURE_TTL
            val authorized =
                ReverseAuthorization(record.swapId, deadline, reverseKeys.signReady(record, deadline).hex())
            record = record.copy(ready = authorized, phase = ReversePhase.SETTLING, receiveEstimate = estimate)
            store.save(record)
            api.ready(authorized)
        }

    suspend fun cancel(index: Int) =
        lock.withLock {
            val record = current(index)
            if (record.finished) return@withLock
            val cancelled = record.copy(cancelRequested = true, phase = ReversePhase.REFUND_WAIT)
            store.save(cancelled)
            advanceLocked(cancelled)
        }

    suspend fun rescue(index: Int) = lock.withLock { refunds.rescue(current(index)) }

    suspend fun canRescue(index: Int): Boolean = lock.withLock { refunds.canRescue(current(index)) }

    private suspend fun advanceLocked(record: ReverseSwapRecord) {
        val observed = verifier.state(record)
        val escrow = observed.swap
        when {
            escrow == null -> {
                awaitFunding(record, observed)
            }

            escrow.stage == SwapStage.CLAIMED -> {
                receive(record, escrow.secret)
            }

            needsRefund(record, observed) -> {
                refunds.advance(record, observed)
            }

            escrow.stage == SwapStage.READY -> {
                store.save(record.copy(phase = ReversePhase.SETTLING))
            }

            record.ready != null -> {
                if (observed.now <= record.ready.deadline && escrow.refundLockUntil == 0L) api.ready(record.ready)
                val phase =
                    if (observed.now >
                        record.ready.deadline
                    ) {
                        ReversePhase.REFUND_WAIT
                    } else {
                        ReversePhase.SETTLING
                    }
                store.save(record.copy(phase = phase))
            }

            else -> {
                awaitDeposit(record)
            }
        }
    }

    private fun needsRefund(record: ReverseSwapRecord, observed: ReverseChainState): Boolean =
        record.cancelRequested ||
            when (observed.swap?.stage) {
                SwapStage.REFUNDED -> true
                SwapStage.READY -> observed.now >= record.quote.refundAfter
                SwapStage.OPEN -> observed.now >= record.quote.readyDeadline
                else -> false
            }

    private suspend fun awaitFunding(record: ReverseSwapRecord, observed: ReverseChainState) {
        val transaction = record.funding
        if (transaction == null) {
            when {
                record.cancelRequested || observed.now >= record.quote.fundingDeadline -> {
                    store.save(record.copy(phase = ReversePhase.CANCELLED))
                }

                record.account == null -> {
                    acceptAndImport(record)
                }
            }
        } else {
            when (chain.fundingStatus(transaction.txId)) {
                ReverseTransactionStatus.REVERTED -> {
                    store.save(record.copy(phase = ReversePhase.CANCELLED))
                }

                ReverseTransactionStatus.UNKNOWN -> {
                    funding.submit(transaction)
                }

                ReverseTransactionStatus.PENDING, ReverseTransactionStatus.CONFIRMED -> {
                    store.save(record.copy(phase = ReversePhase.CONFIRMING_ESCROW))
                }

                ReverseTransactionStatus.EXPIRED -> {
                    Unit
                }
            }
        }
    }

    private suspend fun awaitDeposit(record: ReverseSwapRecord) {
        val spendable = zcash.spendable(record)
        val observed = verifier.state(record)
        val phase =
            when {
                observed.swap?.stage != SwapStage.OPEN -> {
                    ReversePhase.SETTLING
                }

                observed.swap.refundLockUntil != 0L || observed.now + SIGNATURE_TTL >= record.quote.readyDeadline -> {
                    ReversePhase.REFUND_WAIT
                }

                spendable >= record.quote.terms.depositZat -> {
                    ReversePhase.AWAITING_READY
                }

                else -> {
                    ReversePhase.RECEIVING_ZEC
                }
            }
        val estimate =
            if (phase ==
                ReversePhase.AWAITING_READY
            ) {
                zcash.estimateReceive(record)
            } else {
                record.receiveEstimate
            }
        store.save(record.copy(phase = phase, receiveEstimate = estimate))
    }

    private suspend fun acceptAndImport(record: ReverseSwapRecord): ReverseSwapRecord {
        check(
            fixedHex(
                api.accept(record.quote.terms.quoteId, record.acceptance),
                SWAP_WORD_BYTES
            ).contentEquals(fixedHex(record.swapId, SWAP_WORD_BYTES))
        )
        val account = zcash.importAccount(record)
        return record.copy(account = account, phase = ReversePhase.AWAITING_FUNDING).also { store.save(it) }
    }

    private suspend fun receive(initial: ReverseSwapRecord, secret: ByteArray) {
        var record = initial
        var observed = record.receive?.let { zcash.receiveStatus(record) }
        if (observed?.status == ReverseTransactionStatus.EXPIRED) {
            record = record.copy(receive = null, receiveConfirmations = 0)
            store.save(record)
        }
        if (record.receive == null) {
            val transaction = zcash.prepareReceive(record, secret)
            record = record.copy(receive = transaction, phase = ReversePhase.RECEIVING)
            store.save(record)
            observed = zcash.receiveStatus(record)
        }
        val progress = checkNotNull(observed)
        record =
            record.copy(
                phase =
                    if (progress.status ==
                        ReverseTransactionStatus.CONFIRMED
                    ) {
                        ReversePhase.COMPLETE
                    } else {
                        ReversePhase.RECEIVING
                    },
                receiveConfirmations = progress.confirmations,
            )
        store.save(record)
        if (!record.finished && progress.confirmations == 0L) {
            zcash.submit(checkNotNull(record.receive))
        }
    }

    private suspend fun current(index: Int): ReverseSwapRecord =
        checkNotNull(store.active()).also {
            check(it.index == index && it.deployment == deployment)
        }

    companion object {
        const val SIGNATURE_TTL = 120L
        const val REVEAL_MARGIN = 300L
        const val MIN_TIME_TO_READY = 25 * 60L
    }
}
