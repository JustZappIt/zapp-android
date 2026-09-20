// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import android.content.ClipboardManager
import android.content.Context
import co.electriccoin.zcash.spackle.getSystemService

/**
 * Reads the clipboard, once, because someone asked for it to be read.
 *
 * Android tells the user when an app reads the clipboard, which is why this is only ever called
 * from an explicit tap. The text is handed back and never kept.
 */
class GetClipboardTextUseCase(
    private val context: Context,
) {
    operator fun invoke(): String? =
        runCatching {
            context
                .getSystemService<ClipboardManager>()
                .primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
        }.getOrNull()
}
