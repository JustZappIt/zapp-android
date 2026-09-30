// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployment
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressArgs
import co.electriccoin.zcash.ui.screen.privateusd.reverse.PrivateUsdReverseArgs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Seconds before a quote's expiry that it stops being offered: the driver won't accept one about to run out. */
internal const val QUOTE_EXPIRY_MARGIN_SECONDS = 20L

/** How long typing pauses before its amount is quoted. */
internal val TYPING_DEBOUNCE = 700.milliseconds

private const val ZEC_DECIMALS = 8
private const val MAX_ZATOSHI = 2_100_000_000_000_000L

/** The deployment conversions run on; their screens only open in builds that have one. */
internal fun AtomicSwapRepository.requireDeployment(): AtomicSwapDeployment =
    checkNotNull(deployment) { "no conversions in this build" }

/** Replaces this screen with the conversion under way, forward or reverse; false when none is. */
internal suspend fun NavigationRouter.showConversionUnderWay(
    forward: AtomicSwapRepository,
    reverse: ReverseSwapRepository,
): Boolean {
    val screen =
        runConversionStep("no conversion under way to show") {
            when {
                forward.isUnderWay() -> PrivateUsdProgressArgs
                reverse.isUnderWay() -> PrivateUsdReverseArgs
                else -> null
            }
        }.getOrNull()
    screen?.let { replace(it) }
    return screen != null
}

/** What to say about a quote that didn't come; nothing when the conversion under way that held it up shows instead. */
internal suspend fun NavigationRouter.quoteFailure(
    error: Throwable,
    forward: AtomicSwapRepository,
    reverse: ReverseSwapRepository,
): StringResource? {
    val failure = error.toFailure()
    val redirected = failure == PrivateUsdFailure.SWAP_UNDER_WAY && showConversionUnderWay(forward, reverse)
    return failure.takeUnless { redirected }?.message(R.string.convert_error_generic)
}

/** The time in epoch seconds, now and every [period] after, for as long as it's collected. */
internal fun epochSeconds(period: Duration): Flow<Long> =
    flow {
        while (true) {
            emit(Clock.System.now().epochSeconds)
            delay(period)
        }
    }

/** A typed ZEC amount in zatoshi; null for none, or one no wallet could hold. */
internal fun NumberTextFieldInnerState.zatoshi(): Long? =
    try {
        amount
            ?.movePointRight(ZEC_DECIMALS)
            ?.longValueExact()
            ?.takeIf { it in 1..MAX_ZATOSHI }
    } catch (_: ArithmeticException) {
        null
    }

/** Something more than zero was typed: a zero is still being typed, so it isn't flagged. */
internal val NumberTextFieldInnerState.isPositive: Boolean get() = amount?.signum() == 1

/** [zatoshi] as a ZEC amount field shows it. */
internal fun zecField(zatoshi: Long): NumberTextFieldInnerState =
    NumberTextFieldInnerState.fromAmount(BigDecimal.valueOf(zatoshi, ZEC_DECIMALS))
