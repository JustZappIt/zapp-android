// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import co.electriccoin.zcash.ui.common.model.voting.VoteOptionDisplayColor
import co.electriccoin.zcash.ui.design.theme.ZappTheme

data class VoteColors(
    val bg: Color,
    val labelColor: Color,
    val textColor: Color,
)

/**
 * Upstream tints each answer from the Zashi utility ramps; the fork has no such ramps, so support
 * and oppose borrow the success/danger pair the rest of the app already reads as yes/no, abstain
 * sits on the muted chip surface, and anything else takes the accent. The soft variants are the
 * only fills here — the shape stays square, as everywhere else.
 */
@Composable
fun VoteOptionDisplayColor.answerColors(): VoteColors {
    val c = ZappTheme.colors
    return when (this) {
        VoteOptionDisplayColor.SUPPORT -> {
            VoteColors(bg = c.successSoft, labelColor = c.success, textColor = c.success)
        }

        VoteOptionDisplayColor.OPPOSE -> {
            VoteColors(bg = c.dangerSoft, labelColor = c.danger, textColor = c.danger)
        }

        VoteOptionDisplayColor.ABSTAIN, VoteOptionDisplayColor.GRAY -> {
            VoteColors(bg = c.chipBg, labelColor = c.textMuted, textColor = c.textMuted)
        }

        else -> {
            VoteColors(bg = c.accentSoft, labelColor = c.accentText, textColor = c.accentText)
        }
    }
}
