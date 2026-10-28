// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.security

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How long Zapp may be away before it asks to authenticate again on its return: the gate of
 * `AuthenticationViewModel.runAuthenticationRequiredCheck`. The Shielded USD page follows the gate: it closes when the
 * gate is up, and after this long in the background (`RailgunEngine.onAppLock`), so a sync carries on across a
 * shorter one.
 */
internal val AUTHENTICATE_TIMEOUT: Duration = 15.minutes
