// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.common.security.SecretAuthPolicy
import co.electriccoin.zcash.ui.design.util.stringRes

/** Asks for the app lock the user set, as the wallet's own send does; false when they back out. */
internal suspend fun SecretAuthGate.authenticateSpend(): Boolean =
    authenticate(
        promptMessage =
            stringRes(
                R.string.authentication_system_ui_subtitle,
                stringRes(R.string.authentication_use_case_send_funds)
            ),
        policy = SecretAuthPolicy.ALLOW_UNCONFIGURED,
    )
