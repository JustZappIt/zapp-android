// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import cash.z.ecc.android.sdk.model.Zatoshi
import java.math.BigDecimal

/**
 * A positive, exactly representable gift-card amount within the Zcash monetary range.
 *
 * Keeping the conversion behind this type prevents a decimal UI value from reaching funding after
 * it has been truncated to eight decimal places, or from throwing while it is converted to
 * [Zatoshi].
 */
@JvmInline
internal value class GiftAmount private constructor(
    val zatoshi: Zatoshi,
) {
    companion object {
        fun fromZec(amount: BigDecimal?): GiftAmount? =
            amount
                ?.takeIf { it > BigDecimal.ZERO }
                ?.toZatoshiExactOrNull()
                ?.takeIf { it <= Zatoshi.MAX_INCLUSIVE }
                ?.let { GiftAmount(Zatoshi(it)) }
    }
}

/** Multiplication followed by an exact integer conversion rejects fractional zatoshi and overflow. */
private fun BigDecimal.toZatoshiExactOrNull(): Long? =
    try {
        multiply(BigDecimal.valueOf(Zatoshi.ZATOSHI_PER_ZEC)).longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
