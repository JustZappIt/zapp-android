// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.deletewallet

/** How [ResetZashiUseCase] ended: the wallet is gone, or the user never authenticated and nothing was touched. */
enum class ResetZashiResult {
    /** The destructive tail ran to its end; a step that timed out or failed on the way was logged and skipped. */
    WIPED,

    /** The biometric prompt was cancelled or failed: nothing was touched. */
    NOT_AUTHENTICATED,
}
