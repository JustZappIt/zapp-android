// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.hd.EvmKeyDerivation

/**
 * The private (NEAR Confidential Intents) account's key, derived from the recovery phrase. Its account ID is the
 * lowercase 0x address. Changing [HD_ACCOUNT] strands every existing holding.
 */
object IntentsAccount {
    const val HD_ACCOUNT = 7
    const val DERIVATION_PATH = "m/44'/60'/$HD_ACCOUNT'/0/0"

    fun derive(mnemonic: CharArray, passphrase: String = ""): EvmKey =
        EvmKeyDerivation.deriveAccount(mnemonic, account = HD_ACCOUNT, passphrase = passphrase)

    fun accountId(key: EvmKey): String = key.address.lowercaseHex
}
