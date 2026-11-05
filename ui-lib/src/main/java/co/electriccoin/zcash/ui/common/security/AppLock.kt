// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.security

/**
 * Zapp's lock as `MainActivity` reports it: open in the foreground with its authentication gate passed or off
 * ([UNLOCKED]); in the background, or not known yet ([LOCKED]); or in the foreground asking to authenticate again
 * ([GATE_UP]), which it does once it was away for [AUTHENTICATE_TIMEOUT]. Shielded USD follows it
 * (`RailgunWalletRepository.onAppLock`).
 */
enum class AppLock { UNLOCKED, LOCKED, GATE_UP }
