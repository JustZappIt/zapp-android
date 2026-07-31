// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.types

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class ChainId(
    val value: Long
) {
    init {
        require(value > 0) { "ChainId must be positive, got $value" }
    }

    val hex: String get() = "0x" + value.toString(HEX_BASE)

    override fun toString(): String = value.toString()

    companion object {
        private const val HEX_BASE = 16

        val BASE_SEPOLIA: ChainId = ChainId(84_532L)
        val BASE_MAINNET: ChainId = ChainId(8_453L)
    }
}
