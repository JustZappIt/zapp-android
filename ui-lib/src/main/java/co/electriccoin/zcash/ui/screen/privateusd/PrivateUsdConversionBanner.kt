// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

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
import androidx.compose.ui.text.font.FontWeight
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdConversion
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowChevron
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.progress.PrivateUsdProgressArgs
import co.electriccoin.zcash.ui.screen.privateusd.reverse.PrivateUsdReverseArgs
import co.electriccoin.zcash.ui.screen.privateusd.reverse.stageLabel
import xyz.justzappit.offramp.atomicswap.ReversePhase

/** The screen this conversion's progress shows on. */
internal val PrivateUsdConversion.progressArgs: Any
    get() =
        when (this) {
            is PrivateUsdConversion.ToUsd -> PrivateUsdProgressArgs
            is PrivateUsdConversion.ToZec -> PrivateUsdReverseArgs
        }

/** How every screen shows a conversion under way: tapped, it opens the conversion's progress. */
internal fun PrivateUsdConversion.banner(onClick: () -> Unit): PrivateUsdConversionBannerState {
    val isAttention =
        when (this) {
            is PrivateUsdConversion.ToUsd -> swap.problem != null
            is PrivateUsdConversion.ToZec -> problem != null || record.phase == ReversePhase.AWAITING_READY
        }
    return PrivateUsdConversionBannerState(
        title =
            stringRes(
                when {
                    isAttention -> R.string.private_usd_banner_attention_title
                    this is PrivateUsdConversion.ToUsd -> R.string.private_usd_banner_title
                    else -> R.string.private_usd_converting_zec
                }
            ),
        detail =
            when (this) {
                is PrivateUsdConversion.ToUsd -> swap.stageDetail(confirmationsNeeded)
                is PrivateUsdConversion.ToZec -> stringRes(record.phase.stageLabel())
            },
        isAttention = isAttention,
        onClick = onClick,
    )
}

/** A conversion under way, one tap from its progress. */
@Composable
internal fun PrivateUsdConversionBanner(
    state: PrivateUsdConversionBannerState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    ZappBorderedCard(
        modifier =
            modifier.clickable(
                onClickLabel = stringResource(R.string.private_usd_banner_view),
                role = Role.Button,
                onClick = state.onClick,
            ),
        borderColor = if (state.isAttention) c.danger else c.border,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZappSectionLabel(text = state.title.getValue(), color = if (state.isAttention) c.danger else c.accent)
                Spacer(Modifier.height(ZappTheme.spacing.sm))
                BasicText(
                    text = state.detail.getValue(),
                    style = ZappTheme.typography.rowTitle.copy(color = c.text, fontWeight = FontWeight.Black),
                )
            }
            Spacer(Modifier.width(ZappTheme.spacing.lg))
            ZappRowChevron()
        }
    }
}

@PreviewScreens
@Composable
private fun PrivateUsdConversionBannerPreview() =
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
            PrivateUsdConversionBanner(
                state =
                    PrivateUsdConversionBannerState(
                        title = stringRes("Conversion needs attention"),
                        detail = stringRes("Authorize settlement"),
                        isAttention = true,
                        onClick = {},
                    ),
            )
        }
    }
