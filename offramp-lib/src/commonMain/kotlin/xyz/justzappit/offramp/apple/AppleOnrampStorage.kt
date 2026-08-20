// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

interface AppleOnrampStorage {
    @Throws(Exception::class)
    fun checkpointJson(): AppleStorageValue

    @Throws(Exception::class)
    fun storeCheckpointJson(value: String)

    @Throws(Exception::class)
    fun clearCheckpoint()
}

interface AppleOnrampDeviceSignals {
    @Throws(Exception::class)
    suspend fun collect(): AppleOnrampDeviceSignalsRecord
}

interface AppleOnrampZecSwapGateway {
    @Throws(Exception::class)
    suspend fun quote(accountAddress: String, usdcMicros: String): AppleZecSwapQuote

    @Throws(Exception::class)
    suspend fun notifyDeposit(baseTransactionHash: String, depositAddress: String)

    @Throws(Exception::class)
    suspend fun status(depositAddress: String): AppleZecSwapStatus
}
