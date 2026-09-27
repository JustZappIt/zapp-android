// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapProblem
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapStage
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlin.time.Duration

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
            AtomicSwapProblem.MAKER_UNREACHABLE -> R.string.convert_problem_maker
            AtomicSwapProblem.ETHEREUM_UNREACHABLE -> R.string.convert_problem_ethereum
            AtomicSwapProblem.RAILGUN_CLOSED -> R.string.convert_problem_railgun
            AtomicSwapProblem.CLAIM_TURN -> R.string.convert_problem_turn
            AtomicSwapProblem.ZCASH_WALLET -> R.string.convert_problem_wallet
            AtomicSwapProblem.UNEXPECTED -> R.string.convert_problem_unexpected
        }
    )

/** The stage under way, with the confirmation count while there is one. */
internal fun AtomicSwapState.stageDetail(confirmationsNeeded: Int?): StringResource {
    val stage = AtomicSwapStage.of(this)
    val seen = confirmations
    return if (stage == AtomicSwapStage.CONFIRMING && seen != null && confirmationsNeeded != null) {
        stringRes(stage.label) + " · " +
            stringRes(R.string.convert_step_confirm_count, seen.coerceAtMost(confirmationsNeeded), confirmationsNeeded)
    } else {
        stringRes(stage.label)
    }
}
