// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A card's suggested expiry, ready to render.
 *
 * [isPast] is advisory and must never be shown as a refusal: nothing on chain enforces an expiry,
 * so a card past its date still claims for its full value.
 */
internal data class GiftExpiryDisplay(
    val date: StringResource,
    val isPast: Boolean,
)

/** Null when there is no expiry, or when it was stamped in a format this build cannot read. */
internal fun String?.toGiftExpiryDisplay(now: Instant = Clock.System.now()): GiftExpiryDisplay? {
    val date = this?.toGiftDisplayDate() ?: return null
    val isPast = runCatching { Instant.parse(this) < now }.getOrDefault(false)
    return GiftExpiryDisplay(date = date, isPast = isPast)
}

/** A record written by a build that stamped something else must not take the screen down with it. */
internal fun String.toGiftDisplayDate(): StringResource? =
    try {
        stringResByDateTime(ZonedDateTime.parse(this), useFullFormat = true)
    } catch (_: DateTimeParseException) {
        null
    }
