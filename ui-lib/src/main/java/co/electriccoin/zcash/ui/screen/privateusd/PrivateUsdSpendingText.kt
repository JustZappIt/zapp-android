// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

internal fun PrivateUsdSpendStatus.message(): StringResource? =
    when (this) {
        PrivateUsdSpendStatus.AVAILABLE -> null
        PrivateUsdSpendStatus.CHECKING -> stringRes(R.string.private_usd_checking_payments)
        PrivateUsdSpendStatus.SENDING -> stringRes(R.string.private_usd_payment_pending)
        PrivateUsdSpendStatus.CONVERTING -> stringRes(R.string.private_usd_conversion_pending)
        PrivateUsdSpendStatus.UNREADABLE -> stringRes(R.string.convert_error_store)
    }
