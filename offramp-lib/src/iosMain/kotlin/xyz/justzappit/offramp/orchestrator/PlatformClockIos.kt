// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun platformCurrentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()

private const val MILLIS_PER_SECOND = 1_000.0
