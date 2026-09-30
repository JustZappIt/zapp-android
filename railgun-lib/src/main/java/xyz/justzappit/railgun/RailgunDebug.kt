// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import java.math.BigInteger

// What only a debug build's page answers: an engine started without `debug` refuses both.

/** The gas account, and the Sepolia ETH it holds to pay for sends. */
suspend fun RailgunSession.gasAccount(): RailgunGasAccount =
    page.request(RailgunMethod.GAS_ACCOUNT, RailgunSession.EMPTY, RailgunGasAccount.serializer())

/** Wraps [amount] wei of the gas account's ETH to shield into this wallet; signed, not sent. */
suspend fun RailgunSession.signShield(amount: BigInteger): RailgunSignedTransaction =
    signed(RailgunMethod.SHIELD, ShieldParams(amount), ShieldParams.serializer())
