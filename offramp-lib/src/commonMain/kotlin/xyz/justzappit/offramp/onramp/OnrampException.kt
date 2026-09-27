// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.onramp

/**
 * A refusal the driver can name before anything is placed — an amount above every limit this
 * wallet holds, a day's orders used up. [code] is what callers branch on; [message] is for logs.
 */
class OnrampException(
    val code: OnrampFailureCode,
    val httpStatus: Int,
    override val message: String,
) : Exception(message)
