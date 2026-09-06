// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.orchestrator

/** Sink for the orchestrator's diagnostic lines; the host routes them to its own logger. */
interface OfframpLogger {
    fun info(message: String)

    fun warn(message: String)

    object None : OfframpLogger {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }
}
