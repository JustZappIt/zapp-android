// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import android.content.Intent

// The link travels alone: any title or text would tell the receiving app something about the group.
class ShareGroupLinkUseCase(
    private val context: Context,
) {
    operator fun invoke(link: String): Boolean =
        runCatching {
            val share =
                Intent(Intent.ACTION_SEND).apply {
                    type = PLAIN_TEXT_MIME_TYPE
                    putExtra(Intent.EXTRA_TEXT, link)
                }
            context.startActivity(
                Intent.createChooser(share, null).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }.isSuccess

    private companion object {
        const val PLAIN_TEXT_MIME_TYPE = "text/plain"
    }
}
