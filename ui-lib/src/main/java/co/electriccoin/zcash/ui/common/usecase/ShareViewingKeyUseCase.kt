// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import android.content.Intent

class ShareViewingKeyUseCase(
    private val context: Context,
) {
    operator fun invoke(
        viewingKey: String,
        sharePickerText: String,
    ): Boolean =
        runCatching {
            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = PLAIN_TEXT_MIME_TYPE
                    putExtra(Intent.EXTRA_TEXT, viewingKey)
                }
            context.startActivity(
                Intent.createChooser(shareIntent, sharePickerText).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.isSuccess

    private companion object {
        const val PLAIN_TEXT_MIME_TYPE = "text/plain"
    }
}
