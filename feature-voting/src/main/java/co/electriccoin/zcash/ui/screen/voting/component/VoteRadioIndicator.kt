// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * A pick, drawn square. Upstream stacks two round radio drawables; the fork has no round anything,
 * so the mark is a filled inset inside a bordered box, and the same spring keeps the selection
 * feeling like a press rather than a repaint.
 */
@Composable
fun VoteRadioIndicator(isChecked: Boolean) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .size(INDICATOR)
                .background(c.surface, RectangleShape)
                .border(1.dp, if (isChecked) c.accent else c.borderStrong, RectangleShape),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = isChecked,
            enter = scaleIn(spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy)),
            exit = scaleOut(spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioMediumBouncy))
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(INSET)
                        .size(MARK)
                        .background(c.accent, RectangleShape)
            )
        }
    }
}

private val INDICATOR = 20.dp
private val MARK = 10.dp
private val INSET = 1.dp
