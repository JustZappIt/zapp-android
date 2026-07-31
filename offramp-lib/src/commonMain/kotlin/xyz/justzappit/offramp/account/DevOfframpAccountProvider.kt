// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.account

import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes

// TESTNET ONLY. Private key is checked into source — anyone with this repo can spend
// whatever sits in the account. For mainnet use [StaticOfframpAccountProvider].
object DevOfframpAccountProvider : OfframpAccountProvider {
    // Random 32 bytes, generated once for this repo. Not derived from any wallet seed.
    private const val DEV_PRIVATE_KEY_HEX =
        "0x46af9e1d2e3a8c5b71f9a0e6d4c2b8a31f5907e8d2c4a6f3e9b7d5c1a8f6e4d2"

    val key: EvmKey by lazy {
        EvmKeyDerivation.fromPrivateKey(DEV_PRIVATE_KEY_HEX.removePrefix("0x").hexToBytes())
    }

    val address: Address get() = key.address

    override suspend fun nextOfframpAccount(): EvmKey = key
}
