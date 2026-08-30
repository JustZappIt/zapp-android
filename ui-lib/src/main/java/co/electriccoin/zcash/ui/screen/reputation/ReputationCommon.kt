// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappSpacing
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * The muted block both reputation screens end on. Byte-identical in each of them before this, which
 * is one edit away from two screens that quietly stop matching.
 */
@Composable
internal fun ReputationNotice(text: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(ZappTheme.colors.surfaceAlt)
                .padding(REPUTATION_NOTICE_PADDING),
    ) {
        BasicText(text, style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted))
    }
}

// The measurements the two reputation screens share. Anything that lands on a ZappSpacing token
// uses the token; the three that do not (18/14/10) are off-scale and stay literal here rather than
// being rounded onto the scale, which would move a layout to tidy a constant.

internal val REPUTATION_HORIZONTAL_PADDING = 18.dp
internal val REPUTATION_VERTICAL_PADDING = ZappSpacing.xl
internal val REPUTATION_BOTTOM_BAR_GAP = ZappSpacing.lg
internal val REPUTATION_SECTION_GAP = ZappSpacing.xl
internal val REPUTATION_NOTICE_PADDING = ZappSpacing.lg

/** Android's minimum touch target, and what the summary row's info button uses. */
internal val REPUTATION_INFO_TAP_TARGET = ZappSpacing.xl6
