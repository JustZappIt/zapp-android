// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6

sealed class PeerCashOutStatus {
    data object Idle : PeerCashOutStatus()

    /**
     * Emitted twice: once while the curator is being asked, then again carrying the [payeeHash] it
     * returned. The hash is what the deposit points at, and the only part of a payee the app is
     * allowed to persist.
     */
    data class ValidatingPayee(
        val platform: PeerPlatform,
        val payeeHash: PayeeHash? = null,
    ) : PeerCashOutStatus()

    /**
     * A ZEC to USDC bridge is in flight to cover the shortfall. [depositAddress] is the 1-Click
     * handle, persisted the moment it exists and before any ZEC moves, because it is the one piece
     * of state that exists nowhere on Base.
     */
    data class BridgingFunds(
        val amount: Usdc6,
        val depositAddress: String? = null,
    ) : PeerCashOutStatus()

    data class FundedFromBase(
        val amount: Usdc6,
        val baseBalance: Usdc6,
    ) : PeerCashOutStatus()

    data class ApprovingUsdc(
        val txHash: TxHash,
        val amount: Usdc6,
    ) : PeerCashOutStatus()

    /**
     * Emitted twice: once with a null [txHash] carrying the [fromBlockNumber] read immediately
     * before broadcasting, then again once the submission returns. The first emission is what a
     * collector persists to make the send recoverable, so the block number is never absent: without
     * it a submission whose hash never came back would have left no trace at all.
     *
     * The point of no return: after this the USDC belongs to the protocol until a fill or a
     * withdrawal.
     */
    data class CreatingDeposit(
        val amount: Usdc6,
        val fromBlockNumber: String,
        val txHash: TxHash? = null,
    ) : PeerCashOutStatus()

    /**
     * The order exists on chain and everything about it is read from [snapshot]. There is no
     * separate "partially filled" or "stopped matching" state: both are properties of the deposit,
     * and duplicating them locally is what makes a waiting screen disagree with the chain.
     */
    data class OrderLive(
        val snapshot: PeerOrderSnapshot,
    ) : PeerCashOutStatus()

    data class Withdrawing(
        val depositId: PeerDepositId,
        val amount: Usdc6,
        val txHash: TxHash? = null,
    ) : PeerCashOutStatus()

    /** Terminal for the unwind: the funds are back in the smart account as USDC, not ZEC. */
    data class Withdrawn(
        val depositId: PeerDepositId,
        val amount: Usdc6,
        val txHash: TxHash? = null,
    ) : PeerCashOutStatus()

    data class Failed(
        val step: PeerCashOutStep,
        val error: PeerError,
        val depositId: PeerDepositId? = null,
    ) : PeerCashOutStatus() {
        /** Derived from the recovery rather than carried alongside it, so the two cannot disagree. */
        val txHash: TxHash? get() = (error.recovery as? PeerRecovery.InspectBaseTransaction)?.txHash
    }
}

enum class PeerCashOutStep {
    INITIALIZATION,
    VALIDATING_PAYEE,
    FUNDING,
    APPROVING_USDC,
    CREATING_DEPOSIT,
    AWAITING_BUYER,
    SETTLING,
    WITHDRAWING,
    ;

    companion object {
        /** Steps surfaced to the progress indicator. INITIALIZATION is internal. */
        val UI_PROGRESS: List<PeerCashOutStep> =
            listOf(
                VALIDATING_PAYEE,
                FUNDING,
                APPROVING_USDC,
                CREATING_DEPOSIT,
                AWAITING_BUYER,
                SETTLING,
                WITHDRAWING,
            )
    }
}

val PeerCashOutStatus.step: PeerCashOutStep
    get() =
        when (this) {
            PeerCashOutStatus.Idle -> {
                PeerCashOutStep.INITIALIZATION
            }

            is PeerCashOutStatus.ValidatingPayee -> {
                PeerCashOutStep.VALIDATING_PAYEE
            }

            is PeerCashOutStatus.BridgingFunds -> {
                PeerCashOutStep.FUNDING
            }

            is PeerCashOutStatus.FundedFromBase -> {
                PeerCashOutStep.FUNDING
            }

            is PeerCashOutStatus.ApprovingUsdc -> {
                PeerCashOutStep.APPROVING_USDC
            }

            is PeerCashOutStatus.CreatingDeposit -> {
                PeerCashOutStep.CREATING_DEPOSIT
            }

            is PeerCashOutStatus.OrderLive -> {
                if (snapshot.liveIntents.isNotEmpty() || snapshot.soldAmount > Usdc6.ZERO) {
                    PeerCashOutStep.SETTLING
                } else {
                    PeerCashOutStep.AWAITING_BUYER
                }
            }

            is PeerCashOutStatus.Withdrawing -> {
                PeerCashOutStep.WITHDRAWING
            }

            is PeerCashOutStatus.Withdrawn -> {
                PeerCashOutStep.WITHDRAWING
            }

            is PeerCashOutStatus.Failed -> {
                step
            }
        }

/** The on-chain deposit once one exists, else null. */
val PeerCashOutStatus.depositId: PeerDepositId?
    get() =
        when (this) {
            is PeerCashOutStatus.OrderLive -> snapshot.id

            is PeerCashOutStatus.Withdrawing -> depositId

            is PeerCashOutStatus.Withdrawn -> depositId

            is PeerCashOutStatus.Failed -> depositId

            PeerCashOutStatus.Idle,
            is PeerCashOutStatus.ValidatingPayee,
            is PeerCashOutStatus.BridgingFunds,
            is PeerCashOutStatus.FundedFromBase,
            is PeerCashOutStatus.ApprovingUsdc,
            is PeerCashOutStatus.CreatingDeposit,
            -> null
        }
