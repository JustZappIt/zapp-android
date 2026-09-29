// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

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

internal enum class PrivateUsdConversionDirection { ZEC_TO_USD, USD_TO_ZEC }

@Composable
internal fun PrivateUsdConversionDirection(
    direction: PrivateUsdConversionDirection,
    onSelect: (PrivateUsdConversionDirection) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_send_convert),
            contentDescription = stringResource(R.string.convert_swap_direction),
            colorFilter = ColorFilter.tint(ZappTheme.colors.accentText),
            modifier =
                Modifier
                    .size(ZappTheme.spacing.xl6)
                    .clickable(role = Role.Button) {
                        onSelect(
                            if (direction ==
                                PrivateUsdConversionDirection.ZEC_TO_USD
                            ) {
                                PrivateUsdConversionDirection.USD_TO_ZEC
                            } else {
                                PrivateUsdConversionDirection.ZEC_TO_USD
                            }
                        )
                    }.padding(ZappTheme.spacing.md)
                    .graphicsLayer { rotationZ = VERTICAL_ROTATION },
        )
    }
}

private const val VERTICAL_ROTATION = 90f
