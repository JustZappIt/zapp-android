// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappCompactButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

/** A conversion under way, from the home screen back to its progress. */
@Composable
internal fun PrivateUsdConversionBanner(
    state: PrivateUsdConversionBannerState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val accent = if (state.isAttention) c.danger else c.accent
    ZappBorderedCard(
        modifier =
            modifier
                .clickable(onClick = state.onClick)
                .semantics(mergeDescendants = true) { role = Role.Button },
        borderColor = if (state.isAttention) c.danger else c.border,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZappSectionLabel(text = state.title.getValue(), color = accent)
                Spacer(Modifier.height(ZappTheme.spacing.sm))
                BasicText(
                    text = state.detail.getValue(),
                    style = ZappTheme.typography.rowTitle.copy(color = c.text, fontWeight = FontWeight.Black),
                )
            }
            Spacer(Modifier.width(ZappTheme.spacing.lg))
            ZappCompactButton(text = stringResource(R.string.private_usd_banner_view), onClick = state.onClick)
        }
    }
}

@PreviewScreens
@Composable
private fun PrivateUsdWidgetPreview() =
    ZcashTheme {
        Column {
            PrivateUsdConversionBanner(
                state =
                    PrivateUsdConversionBannerState(
                        title = stringRes("Converting to private USD"),
                        detail = stringRes("Waiting for confirmations · 2 of 3"),
                        isAttention = false,
                        onClick = {},
                    ),
            )
        }
    }
