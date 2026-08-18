// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

import kotlinx.serialization.Serializable

/**
 * Device screening record sent with every order. Merchants filter on it: an order placed without
 * one routes normally and then goes unaccepted, so this is required rather than best-effort.
 *
 * Field names and units follow the browser APIs the service was built against, which is why
 * [timezoneOffset] is minutes *west* of UTC (IST is -330) and [devicePixelRatio] is a density
 * multiplier. `ip` is deliberately absent: the service uses the connecting address.
 */
@Serializable
data class OnrampDeviceSignals(
    val userAgent: String,
    val platform: String,
    val language: String,
    val languages: List<String>,
    val screenWidth: Int,
    val screenHeight: Int,
    val devicePixelRatio: Double,
    val timezone: String,
    val timezoneOffset: Int,
    val cookiesEnabled: Boolean,
    val doNotTrack: String?,
    val online: Boolean,
    val touchSupport: Boolean,
    val maxTouchPoints: Int,
    val vendor: String,
    val appVersion: String,
    val colorDepth: Int,
    val pixelDepth: Int,
    val connectionType: String? = null,
    val deviceMemory: Double? = null,
    val hardwareConcurrency: Int? = null,
    val seonSession: String? = null,
)

fun interface OnrampDeviceSignalsProvider {
    suspend fun collect(): OnrampDeviceSignals
}

/**
 * Supplies SEON's device-intelligence token. Zapp ships without SEON, so the default returns null
 * and the screening record goes out with native signals only.
 */
fun interface OnrampScreeningSessionProvider {
    suspend fun session(): String?

    companion object {
        val ABSENT = OnrampScreeningSessionProvider { null }
    }
}
