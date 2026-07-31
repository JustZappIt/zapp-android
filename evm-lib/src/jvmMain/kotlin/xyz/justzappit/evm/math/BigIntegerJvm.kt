// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.math

actual typealias BigInteger = java.math.BigInteger

actual val bigIntegerZero: BigInteger = BigInteger.ZERO
actual val bigIntegerOne: BigInteger = BigInteger.ONE
actual fun bigIntegerValueOf(value: Long): BigInteger = BigInteger.valueOf(value)
