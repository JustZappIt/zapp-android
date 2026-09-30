// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.progress

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapStage
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.atomicswap.ZcashWait
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.privateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByQuantity
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.about
import co.electriccoin.zcash.ui.screen.privateusd.joinDetail
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.AtomicSwapRecord
import xyz.justzappit.offramp.atomicswap.AtomicSwapWait
import xyz.justzappit.offramp.atomicswap.NothingSentCause
import xyz.justzappit.offramp.atomicswap.RefundCause
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration.Companion.seconds

/** The steps, notes and result a conversion's progress screen shows. */
internal class PrivateUsdProgressSteps(
    private val deployment: AtomicSwapDeployment,
) {
    private val token = deployment.privateUsdToken

    fun of(
        swap: AtomicSwapState,
        balance: PrivateUsdBalanceState
    ): List<ZappStep> {
        val record = swap.record
        return when {
            record == null || record.outcome is AtomicSwapOutcome.Refunded ||
                record.outcome is AtomicSwapOutcome.NothingSent -> {
                emptyList()
            }

            record.outcome == AtomicSwapOutcome.Paid -> {
                paid(record, balance)
            }

            AtomicSwapStage.of(swap) == AtomicSwapStage.REFUNDING -> {
                listOf(ZappStep(stringRes(AtomicSwapStage.REFUNDING.label), ZappStepStatus.InProgress))
            }

            else -> {
                underWay(swap)
            }
        }
    }

    val info =
        PrivateUsdInfo(
            title = stringRes(R.string.convert_progress_info_title),
            steps =
                listOf(
                    stringRes(R.string.convert_progress_info_open),
                    stringRes(R.string.convert_progress_info_deposit),
                    stringResByQuantity(R.plurals.convert_progress_info_confirm, deployment.makerConfirmations),
                    stringRes(R.string.convert_progress_info_claim),
                    stringRes(R.string.convert_progress_info_screen, deployment.screeningTime.about()),
                ),
            notes = listOf(stringRes(R.string.convert_progress_info_refund)),
        )

    /** What the deposit costs in all, network fee included, as the review said, and what it brings. */
    fun amounts(
        record: AtomicSwapRecord,
        currency: LocalCurrency
    ): StringResource =
        stringRes(
            R.string.convert_progress_amounts,
            stringRes(Zatoshi(record.maxTotalZat ?: record.quote.depositZat)),
            received(record, currency)
        )

    fun note(
        swap: AtomicSwapState,
        now: Long,
        isSlowToOpen: Boolean
    ): StringResource? {
        // Enough confirmations and still no word from the maker: the app claims by itself at t0.
        val silentUntil =
            swap.wait
                ?.t0
                ?.takeIf {
                    swap.wait.reason == AtomicSwapWait.CONFIRMING &&
                        (swap.confirmations ?: 0) >= deployment.makerConfirmations
                }?.takeIf { it > now && swap.problem == null }
        return when {
            swap.resuming -> stringRes(R.string.convert_resuming)
            AtomicSwapStage.of(swap) == AtomicSwapStage.REFUNDING -> stringRes(R.string.convert_refunding)
            swap.wait?.reason == AtomicSwapWait.DEPOSIT_UNSETTLED -> stringRes(R.string.convert_unsettled)
            isSlowToOpen -> stringRes(R.string.convert_opening_slow)
            silentUntil != null -> stringRes(R.string.convert_maker_silent, time(silentUntil))
            else -> null
        }
    }

    fun result(
        record: AtomicSwapRecord,
        balance: PrivateUsdBalanceState,
        currency: LocalCurrency,
    ): PrivateUsdResultState? =
        when (val outcome = record.outcome) {
            null -> {
                null
            }

            AtomicSwapOutcome.Paid -> {
                PrivateUsdResultState(
                    title = stringRes(R.string.convert_result_paid_title),
                    body =
                        if (arrival(record, balance) == Arrival.SCREENED) {
                            stringRes(R.string.convert_result_screened_body, received(record, currency))
                        } else {
                            val screening = deployment.screeningTime.about()
                            stringRes(R.string.convert_result_paid_body, received(record, currency), screening)
                        },
                    isSuccess = true,
                )
            }

            is AtomicSwapOutcome.Refunded -> {
                PrivateUsdResultState(
                    title = stringRes(R.string.convert_result_refunded_title),
                    body =
                        stringRes(
                            when (outcome.cause) {
                                RefundCause.MAKER_CANCELLED -> R.string.convert_result_refunded_maker
                                RefundCause.NOT_CLAIMED_IN_TIME -> R.string.convert_result_refunded_late
                            }
                        ),
                    isSuccess = false,
                )
            }

            is AtomicSwapOutcome.NothingSent -> {
                PrivateUsdResultState(
                    title = stringRes(R.string.convert_result_nothing_title),
                    body = stringRes(nothingSentBody(outcome.cause)),
                    isSuccess = false,
                )
            }
        }

    private fun underWay(swap: AtomicSwapState): List<ZappStep> {
        val current = UNDER_WAY.indexOf(AtomicSwapStage.of(swap))
        return UNDER_WAY.mapIndexed { index, step ->
            ZappStep(
                label = stringRes(step.label),
                status =
                    when {
                        index < current -> ZappStepStatus.Completed
                        index == current -> ZappStepStatus.InProgress
                        else -> ZappStepStatus.Pending
                    },
                detailLines =
                    if (index == current) {
                        listOfNotNull(swap.zcashWait?.stepDetail() ?: eta(step, swap))
                    } else {
                        emptyList()
                    },
            )
        } + tail(arrival = null)
    }

    private fun paid(
        record: AtomicSwapRecord,
        balance: PrivateUsdBalanceState,
    ): List<ZappStep> =
        UNDER_WAY.map { ZappStep(stringRes(it.label), ZappStepStatus.Completed) } + tail(arrival(record, balance))

    // Arriving and screening: waiting until the payout, then as far as [arrival] has come.
    private fun tail(arrival: Arrival?): List<ZappStep> {
        val isPaid = arrival != null
        val arrived = arrival == Arrival.ARRIVED || arrival == Arrival.SCREENED
        val screened = arrival == Arrival.SCREENED
        return listOf(
            ZappStep(
                label = stringRes(R.string.convert_step_arrive),
                status =
                    when {
                        arrived -> ZappStepStatus.Completed
                        isPaid -> ZappStepStatus.InProgress
                        else -> ZappStepStatus.Pending
                    },
                detailLines = if (isPaid && !arrived) listOf(stringRes(R.string.convert_eta_minute)) else emptyList(),
            ),
            ZappStep(
                label = stringRes(R.string.convert_step_screen),
                status =
                    when {
                        screened -> ZappStepStatus.Completed
                        arrived -> ZappStepStatus.InProgress
                        else -> ZappStepStatus.Pending
                    },
                detailLines = if (arrived && !screened) listOf(deployment.screeningTime.about()) else emptyList(),
            ),
        )
    }

    // A sync a moment after the payout shows it; screening is over once nothing is pending any more.
    private fun arrival(
        record: AtomicSwapRecord,
        balance: PrivateUsdBalanceState,
    ): Arrival {
        val paidAt = record.end?.at ?: return Arrival.PAID
        val synced = balance.updatedAt?.let { it.epochSeconds >= paidAt + ARRIVAL_LAG_SECONDS } == true
        val pending = (balance.balances?.arriving?.signum() ?: 0) > 0
        return when {
            synced && !pending -> Arrival.SCREENED
            synced || pending -> Arrival.ARRIVED
            else -> Arrival.PAID
        }
    }

    private fun eta(
        step: AtomicSwapStage,
        swap: AtomicSwapState
    ): StringResource? =
        when (step) {
            AtomicSwapStage.OPENING, AtomicSwapStage.CLAIMING -> {
                stringRes(R.string.convert_eta_minute)
            }

            AtomicSwapStage.DEPOSITING -> {
                stringRes(R.string.convert_eta_seconds)
            }

            AtomicSwapStage.CONFIRMING -> {
                val needed = deployment.makerConfirmations
                val seen = (swap.confirmations ?: 0).coerceAtMost(needed)
                val left = BLOCK_TIME * (needed - seen).coerceAtLeast(1)
                joinDetail(stringRes(R.string.convert_step_confirm_count, seen, needed), left.about())
            }

            AtomicSwapStage.REFUNDING -> {
                null
            }
        }

    private fun received(
        record: AtomicSwapRecord,
        currency: LocalCurrency
    ): StringResource {
        val units = record.receives ?: record.quote.amount
        return currency.format(units.micros.toDecimal(token.decimals))
    }

    private companion object {
        val BLOCK_TIME = 75.seconds
        const val ARRIVAL_LAG_SECONDS = 30L
        val UNDER_WAY =
            listOf(
                AtomicSwapStage.OPENING,
                AtomicSwapStage.DEPOSITING,
                AtomicSwapStage.CONFIRMING,
                AtomicSwapStage.CLAIMING,
            )

        fun nothingSentBody(cause: NothingSentCause) =
            when (cause) {
                NothingSentCause.QUOTE_EXPIRED -> R.string.convert_result_quote_expired
                NothingSentCause.MAKER_REFUSED -> R.string.convert_result_maker_refused
                NothingSentCause.MAKER_UNAVAILABLE -> R.string.convert_result_maker_unavailable
                NothingSentCause.NEVER_OPENED -> R.string.convert_result_never_opened
                NothingSentCause.MISMATCH -> R.string.convert_result_mismatch
                NothingSentCause.DEPOSIT_WINDOW_MISSED -> R.string.convert_result_window_missed
                NothingSentCause.MAKER_CANCELLED -> R.string.convert_result_maker_cancelled
            }
    }
}

/** What the step under way says while Zcash holds it up; a deposit's confirmations are what its own step waits for. */
internal fun ZcashWait.stepDetail(): StringResource? =
    when (this) {
        ZcashWait.SYNCING -> stringRes(R.string.convert_wait_syncing)
        ZcashWait.CONFIRMATIONS -> null
    }

private fun time(epochSeconds: Long): String =
    DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

private enum class Arrival { PAID, ARRIVED, SCREENED }
