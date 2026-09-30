// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common

import co.electriccoin.zcash.spackle.Twig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A scope for [name]'s work in the background: what escapes it is logged, never a crash. */
internal fun backgroundScope(name: String): CoroutineScope =
    CoroutineScope(
        Dispatchers.Default +
            SupervisorJob() +
            CoroutineExceptionHandler { _, e -> Twig.error(e) { "$name: background work failed" } },
    )
