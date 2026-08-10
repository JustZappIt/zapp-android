// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

/**
 * Where the onramp service lives, per build flavour. A blank [baseUrl] means the build was made
 * without one and the entry point stays hidden — distinct from the service answering
 * `enabled: false`, which is the operator's own kill switch.
 */
data class OnrampBackendConfig(
    val baseUrl: String,
    val appId: String = OnrampRequestSigner.DEFAULT_APP_ID,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}
