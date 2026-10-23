// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** A spinner small enough to sit in a line of small text, beside what is still on its way. */
@Composable
fun ZappInlineSpinner(
    modifier: Modifier = Modifier,
    color: Color = ZappTheme.colors.textMuted,
) {
    CircularProgressIndicator(
        color = color,
        strokeWidth = STROKE_WIDTH.dp,
        modifier = modifier.size(SIZE.dp),
    )
}

private const val SIZE = 12
private const val STROKE_WIDTH = 1.5f
