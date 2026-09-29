// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapTestnet
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import java.math.BigInteger

internal fun PrivateUsdBalanceState.reverseAvailable(): BigInteger? =
    balances
        ?.assets
        ?.firstOrNull {
            it.token.address.equals(ReverseSwapTestnet.deployment.token, ignoreCase = true)
        }?.available ?: balances?.let { BigInteger.ZERO }
