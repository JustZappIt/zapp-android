// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import android.content.Intent

/**
 * Hands a group invite link to the system share sheet.
 *
 * The link travels alone, with no title and no covering text, because anything added would say
 * something about the group to whatever app the owner picks. Nothing is recorded: unlike a gift
 * card, a link that is shared twice costs nothing, so there is no reason to watch where it went.
 */
class ShareGroupLinkUseCase(
    private val context: Context,
) {
    /** True means the sheet went up, nothing more. */
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
