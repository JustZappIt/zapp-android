// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.R as DesignR

@Composable
fun VoteViewMoreChip(
    onClick: () -> Unit,
    isExpanded: Boolean = false,
) {
    val c = ZappTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs),
        modifier = Modifier.clickable { onClick() }
    ) {
        BasicText(
            text =
                stringResource(
                    if (isExpanded) R.string.coinVote_common_viewLess else R.string.coinVote_common_viewMore
                ),
            style = ZappTheme.typography.caption.copy(color = c.text)
        )
        Icon(
            painter = painterResource(DesignR.drawable.ic_chevron_down_small),
            contentDescription = null,
            tint = c.text,
            modifier =
                Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = if (isExpanded) ROTATE else 0f }
        )
    }
}

private const val ROTATE = 180f
