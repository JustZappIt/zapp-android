// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.proposaldetail.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.R as DesignR

/** Shown when a poll closes underneath someone mid-vote. Results are still worth reading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollEndedBottomSheet(
    onViewResults: () -> Unit,
    onClose: () -> Unit,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = c.bg,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.xl)
                    .padding(bottom = spacing.xl4)
        ) {
            Box(
                modifier = Modifier.size(ICON_TILE).background(c.dangerSoft, RectangleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.ic_info),
                    contentDescription = null,
                    tint = c.danger,
                    modifier = Modifier.size(ICON)
                )
            }

            BasicText(
                text = stringResource(R.string.coinVote_pollClosedSheet_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text, textAlign = TextAlign.Center)
            )
            BasicText(
                text = stringResource(R.string.coinVote_pollClosedSheet_message),
                style = ZappTheme.typography.body.copy(color = c.textMuted, textAlign = TextAlign.Center)
            )

            ZappButton(
                text = stringResource(R.string.coinVote_common_viewResults),
                modifier = Modifier.fillMaxWidth(),
                onClick = onViewResults
            )
            ZappButton(
                text = stringResource(R.string.coinVote_common_close),
                modifier = Modifier.fillMaxWidth(),
                variant = ZappButtonVariant.Ghost,
                onClick = onClose
            )
        }
    }
}

private val ICON_TILE = 48.dp
private val ICON = 24.dp
