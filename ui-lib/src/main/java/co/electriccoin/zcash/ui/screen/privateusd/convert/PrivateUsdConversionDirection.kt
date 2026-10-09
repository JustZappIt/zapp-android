// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.R as DesignR

internal enum class ConvertAsset(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    /** Marks the token as Railgun's private kind. */
    @param:DrawableRes val badge: Int? = null,
) {
    ZEC(R.string.convert_asset_zec, DesignR.drawable.ic_token_zec),
    PRIVATE_USD(R.string.private_usd_title, DesignR.drawable.ic_token_usdc, R.drawable.ic_private_usd_badge),
}

internal enum class PrivateUsdConversionDirection(
    val from: ConvertAsset,
    val to: ConvertAsset,
) {
    ZEC_TO_USD(ConvertAsset.ZEC, ConvertAsset.PRIVATE_USD),
    USD_TO_ZEC(ConvertAsset.PRIVATE_USD, ConvertAsset.ZEC);

    val opposite: PrivateUsdConversionDirection get() = entries.first { it != this }
}

/** Swaps what's converted into what; null [onClick] while the conversion can't change direction. */
@Composable
internal fun PrivateUsdDirectionToggle(onClick: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_send_convert),
            contentDescription = stringResource(R.string.convert_swap_direction),
            colorFilter =
                ColorFilter.tint(if (onClick != null) ZappTheme.colors.accentText else ZappTheme.colors.textSubtle),
            modifier =
                Modifier
                    .size(ZappTheme.spacing.xl6)
                    .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
                    .padding(ZappTheme.spacing.md)
                    .graphicsLayer { rotationZ = VERTICAL_ROTATION },
        )
    }
}

private const val VERTICAL_ROTATION = 90f
