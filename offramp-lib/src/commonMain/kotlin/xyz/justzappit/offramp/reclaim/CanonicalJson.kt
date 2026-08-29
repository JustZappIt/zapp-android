// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reclaim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * RFC 8785 (JCS) serialisation, to the extent Reclaim uses it: object keys sorted, no insignificant
 * whitespace. Reclaim's SDK runs the `canonicalize` package over two things — the payload it signs
 * at init, and the proof context — and both are compared byte-for-byte on the far side, so key
 * order here is not cosmetic. Only strings, booleans and nested objects appear in either, which is
 * why JCS's number-formatting rules are not implemented rather than implemented wrongly.
 */
internal object CanonicalJson {
    private val json = Json

    fun stringify(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), sort(element))

    private fun sort(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sort(it.value) })
            is JsonArray -> JsonArray(element.map(::sort))
            else -> element
        }
}
