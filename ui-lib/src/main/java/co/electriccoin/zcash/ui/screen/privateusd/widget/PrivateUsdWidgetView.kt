// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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

/** Private USD beside the total balance's label: what's spendable, and what's on its way in. */
@Composable
internal fun PrivateUsdBalanceTag(
    state: PrivateUsdWidgetState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val description = stringResource(R.string.private_usd_home_content_description)
    val style = ZappTheme.typography.groupLabel
    Row(
        modifier =
            modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = state.onClick,
                ).semantics {
                    role = Role.Button
                    contentDescription = description
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.sm),
    ) {
        ZappSectionLabel(text = stringResource(R.string.private_usd_title))
        BasicText(
            text = state.balance?.getValue() ?: stringResource(R.string.private_usd_home_loading),
            style =
                style.copy(
                    color =
                        when {
                            state.balance == null -> c.textSubtle
                            state.isBlocked -> c.danger
                            else -> c.text
                        },
                    fontWeight = FontWeight.Black,
                ),
            maxLines = 1,
        )
        state.arriving?.let {
            BasicText(text = it.getValue(), style = style.copy(color = c.accentText), maxLines = 1)
        }
        BasicText(text = "›", style = style.copy(color = c.textSubtle))
    }
}

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
            PrivateUsdBalanceTag(
                state =
                    PrivateUsdWidgetState(
                        balance = stringRes("$12.34"),
                        arriving = stringRes("+$0.98"),
                        isBlocked = false,
                        conversion = null,
                        onClick = {},
                        onConvertClick = {},
                    ),
            )
            Spacer(Modifier.height(ZappTheme.spacing.xl))
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
