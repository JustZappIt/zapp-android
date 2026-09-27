// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the string an ERC-191 intents signer signs, with the key order and compact formatting of
 * `IntentSignerViem.signIntent` (`JSON.stringify` of signer_id, verifying_contract, deadline, nonce,
 * intents). For Zapp's inputs — an 0x account ID, ISO deadline, Base64 nonce and ASCII intents — the string
 * is identical to the SDK's. kotlinx and JS differ on lone surrogates, number spellings and integer-like
 * object keys, none of which these inputs contain. The server verifies whatever string it receives, so a
 * difference would change the bytes signed, never make a signature invalid.
 *
 * Only Zapp's own messages are built here (the empty-intents login). A sell signs the payload string
 * `generate-intent` returns, unchanged, after [IntentTransferSigner] has checked it.
 */
internal object IntentPayload {
    const val VERIFYING_CONTRACT = "intents.near"

    private val EVM_ACCOUNT_ID = Regex("^0x[0-9a-f]{40}$")
    private val ISO_UTC =
        Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,9}))?Z$")

    fun build(
        signerId: String,
        deadlineEpochMillis: Long,
        nonceBase64: String,
        intents: JsonArray = JsonArray(emptyList()),
    ): String {
        require(EVM_ACCOUNT_ID.matches(signerId)) { "signerId must be a lowercase 0x account ID" }
        return buildString {
            append("{\"signer_id\":").append(JsonPrimitive(signerId))
            append(",\"verifying_contract\":").append(JsonPrimitive(VERIFYING_CONTRACT))
            append(",\"deadline\":").append(JsonPrimitive(isoMillis(deadlineEpochMillis)))
            append(",\"nonce\":").append(JsonPrimitive(nonceBase64))
            append(",\"intents\":").append(intents.toString())
            append('}')
        }
    }

    /** `Date.prototype.toISOString` for a UTC instant: `yyyy-MM-ddTHH:mm:ss.SSSZ`, always three fraction digits. */
    fun isoMillis(epochMillis: Long): String {
        require(epochMillis in 0..IntentNonce.MAX_EPOCH_MILLIS) { "epochMillis out of range" }
        val days = epochMillis.floorDiv(MILLIS_PER_DAY)
        val msOfDay = epochMillis.mod(MILLIS_PER_DAY)
        val (year, month, day) = civilFromDays(days)
        val hour = msOfDay / MILLIS_PER_HOUR
        val minute = msOfDay % MILLIS_PER_HOUR / MILLIS_PER_MINUTE
        val second = msOfDay % MILLIS_PER_MINUTE / MILLIS_PER_SECOND
        val milli = msOfDay % MILLIS_PER_SECOND
        return "${pad(year, YEAR_DIGITS)}-${pad(month, FIELD_DIGITS)}-${pad(day, FIELD_DIGITS)}T" +
            "${pad(hour, FIELD_DIGITS)}:${pad(minute, FIELD_DIGITS)}:${pad(second, FIELD_DIGITS)}." +
            "${pad(milli, MILLI_DIGITS)}Z"
    }

    /**
     * The epoch milliseconds of a UTC ISO-8601 instant as intents payloads carry it
     * (`yyyy-MM-ddTHH:mm:ss[.fraction]Z`), or null for anything else. Sub-millisecond digits are dropped.
     */
    @Suppress("MagicNumber", "ReturnCount")
    fun parseIsoMillis(value: String): Long? {
        val groups = ISO_UTC.matchEntire(value)?.groupValues ?: return null
        val year = groups[1].toLong()
        val month = groups[2].toLong()
        val day = groups[3].toLong()
        val hour = groups[4].toLong()
        val minute = groups[5].toLong()
        val second = groups[6].toLong()
        val fraction = groups[7]
        if (month !in 1..12 || day < 1 || day > daysInMonth(year, month)) return null
        if (hour > 23 || minute > 59 || second > 59) return null
        val millis = if (fraction.isEmpty()) 0L else fraction.padEnd(MILLI_DIGITS, '0').take(MILLI_DIGITS).toLong()
        return daysFromCivil(year, month, day) * MILLIS_PER_DAY +
            hour * MILLIS_PER_HOUR + minute * MILLIS_PER_MINUTE + second * MILLIS_PER_SECOND + millis
    }

    // Howard Hinnant's days-to-civil algorithm (proleptic Gregorian, epoch 1970-01-01).
    @Suppress("MagicNumber")
    private fun civilFromDays(daysSinceEpoch: Long): Triple<Long, Long, Long> {
        val z = daysSinceEpoch + 719_468
        val era = z.floorDiv(146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return Triple(year, month, day)
    }

    // The inverse of civilFromDays, from the same source.
    @Suppress("MagicNumber")
    private fun daysFromCivil(year: Long, month: Long, day: Long): Long {
        val y = if (month <= 2) year - 1 else year
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    @Suppress("MagicNumber")
    private fun daysInMonth(year: Long, month: Long): Long =
        when (month) {
            2L -> if (year % 4 == 0L && (year % 100 != 0L || year % 400 == 0L)) 29 else 28
            4L, 6L, 9L, 11L -> 30
            else -> 31
        }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')

    private const val YEAR_DIGITS = 4
    private const val FIELD_DIGITS = 2
    private const val MILLI_DIGITS = 3
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
    private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
    private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
}
