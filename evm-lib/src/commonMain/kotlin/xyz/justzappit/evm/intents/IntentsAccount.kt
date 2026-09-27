// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.intents

import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.hd.EvmKeyDerivation

/**
 * The key behind a user's private (NEAR Confidential Intents) account: `m/44'/60'/7'/0/0` from the wallet's
 * own recovery phrase. The hardened account 7 keeps it apart from the offramp key at `m/44'/60'/0'/0/0` and
 * out of the account range standard Ethereum wallets scan. Changing [HD_ACCOUNT] strands every existing
 * holding, so it is fixed.
 *
 * The Intents account ID for an secp256k1 signer is its lowercase 0x address; no registration or deposit
 * is needed before the account can receive funds or sign in.
 */
object IntentsAccount {
    const val HD_ACCOUNT = 7
    const val DERIVATION_PATH = "m/44'/60'/$HD_ACCOUNT'/0/0"

    fun derive(mnemonic: CharArray, passphrase: String = ""): EvmKey =
        EvmKeyDerivation.deriveAccount(mnemonic, account = HD_ACCOUNT, passphrase = passphrase)

    fun accountId(key: EvmKey): String = key.address.lowercaseHex
}
