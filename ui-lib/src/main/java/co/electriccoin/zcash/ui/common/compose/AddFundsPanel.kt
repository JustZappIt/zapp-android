// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue

/**
 * The empty-wallet explanation described by [AddFundsPanelState]. Display only: the screen puts
 * the Add ZEC action in its bottom bar, beside back, where every Zapp screen keeps its primary action.
 */
@Composable
fun AddFundsPanel(
    state: AddFundsPanelState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(88.dp)
                    .background(c.surfaceAlt, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = c.textSubtle,
            )
        }
        Spacer(Modifier.height(24.dp))
        BasicText(
            text = stringResource(R.string.add_funds_panel_title),
            style =
                ZappTheme.typography.sectionTitle.copy(
                    color = c.text,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                ),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = state.body.getValue(),
            style =
                ZappTheme.typography.body.copy(
                    color = c.textMuted,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}
