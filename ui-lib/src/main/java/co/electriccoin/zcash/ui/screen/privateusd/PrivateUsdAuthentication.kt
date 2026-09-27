// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.design.util.stringRes

/** The same check the wallet asks for before any send; false when the user backed out. */
internal suspend fun BiometricRepository.authorizeSpend(): Boolean =
    try {
        requestBiometrics(
            BiometricRequest(
                message =
                    stringRes(
                        R.string.authentication_system_ui_subtitle,
                        stringRes(R.string.authentication_use_case_send_funds)
                    )
            )
        )
        true
    } catch (_: BiometricsFailureException) {
        false
    } catch (_: BiometricsCancelledException) {
        false
    }
