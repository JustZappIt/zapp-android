// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConversionDirection
import co.electriccoin.zcash.ui.screen.privateusd.convert.PrivateUsdConvertScreen
import kotlinx.serialization.Serializable

@Composable
fun PrivateUsdReverseScreen() = PrivateUsdConvertScreen(initialDirection = PrivateUsdConversionDirection.USD_TO_ZEC)

@Serializable
data object PrivateUsdReverseArgs
