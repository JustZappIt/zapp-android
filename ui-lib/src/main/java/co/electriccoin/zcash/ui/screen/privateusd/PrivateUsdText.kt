// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapProblem
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapStage
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalances
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant

private const val MINUTES_PER_HOUR = 60

/** "about 5 min" or "about 1 h". */
internal fun Duration.about(): StringResource {
    val minutes = inWholeMinutes.coerceAtLeast(1)
    return if (minutes < MINUTES_PER_HOUR) {
        stringRes(R.string.private_usd_about_minutes, minutes.toInt())
    } else {
        stringRes(R.string.private_usd_about_hours, (minutes / MINUTES_PER_HOUR).toInt())
    }
}

internal fun AtomicSwapProblem.message(): StringResource =
    stringRes(
        when (this) {
            AtomicSwapProblem.RELAYER_UNREACHABLE -> R.string.convert_problem_relayer
            AtomicSwapProblem.ETHEREUM_UNREACHABLE -> R.string.convert_problem_ethereum
            AtomicSwapProblem.RAILGUN_CLOSED -> R.string.convert_problem_railgun
            AtomicSwapProblem.CLAIM_TURN -> R.string.convert_problem_turn
            AtomicSwapProblem.ZCASH_WALLET -> R.string.convert_problem_wallet
            AtomicSwapProblem.ZCASH_REJECTED -> R.string.convert_problem_rejected
            AtomicSwapProblem.MISMATCH -> R.string.convert_problem_mismatch
            AtomicSwapProblem.UNEXPECTED -> R.string.convert_problem_unexpected
        }
    )

/** The stage under way, with the confirmation count while there is one. */
internal fun AtomicSwapState.stageDetail(confirmationsNeeded: Int?): StringResource {
    val stage = AtomicSwapStage.of(this)
    val seen = confirmations
    return if (stage == AtomicSwapStage.CONFIRMING && seen != null && confirmationsNeeded != null) {
        joinDetail(
            stringRes(stage.label),
            stringRes(R.string.convert_step_confirm_count, seen.coerceAtMost(confirmationsNeeded), confirmationsNeeded),
        )
    } else {
        stringRes(stage.label)
    }
}

/** Two parts of one line, the way every Private USD detail joins them. */
internal fun joinDetail(
    first: StringResource,
    second: StringResource
): StringResource = stringRes(R.string.private_usd_activity_detail, first, second)

/** When something happened, written the way the wallet's activity writes it. */
internal fun dateTime(at: Instant): StringResource =
    stringResByDateTime(ZonedDateTime.ofInstant(at.toJavaInstant(), ZoneId.systemDefault()), useFullFormat = false)

/** The balance at a glance, in [currency]. */
internal fun PrivateUsdBalances.headline(currency: LocalCurrency): StringResource = currency.format(spendable)

/** What shows in the balance's place before one is known: loading only while a refresh runs. */
internal fun PrivateUsdBalanceState.placeholder(): StringResource =
    stringRes(if (isRefreshing) R.string.private_usd_home_loading else R.string.private_usd_home_unknown)

/** What of [token] can be spent, in [currency], or what stands in for it until that's known. */
internal fun PrivateUsdBalanceState.availableText(
    token: PrivateUsdToken,
    currency: LocalCurrency
): StringResource =
    available(token)?.let { currency.format(it.toDecimal(token.decimals)).asPrivacySensitive() } ?: placeholder()

internal fun PrivateUsdBalances.arrivingTag(currency: LocalCurrency): StringResource? =
    arriving.takeIf { it.signum() > 0 }?.let { stringRes(R.string.private_usd_activity_in, currency.format(it)) }

/** The one line under a balance that needs saying, refused funds first. */
internal fun PrivateUsdBalances.detail(currency: LocalCurrency): StringResource? =
    when {
        isBlocked -> {
            stringRes(R.string.private_usd_home_blocked, currency.format(blocked).asPrivacySensitive())
        }

        arriving.signum() > 0 -> {
            stringRes(R.string.private_usd_home_arriving, currency.format(arriving).asPrivacySensitive())
        }

        else -> {
            null
        }
    }
