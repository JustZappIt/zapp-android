// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toEntropy
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import cash.z.ecc.android.sdk.tool.DerivationTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A throwaway wallet backing one gift card. The mnemonic is the money — never log this. */
class EphemeralGiftKeys(
    val mnemonic: String,
    val address: String,
) {
    override fun toString(): String = "EphemeralGiftKeys(REDACTED)"
}

/**
 * Mints and re-derives the throwaway wallets behind gift cards.
 *
 * Split out from the use cases because it is the only part of the feature that needs the Rust
 * derivation backend: with it behind an interface, everything above can be exercised on the JVM.
 *
 * The key material is random, not derived from the wallet seed at a ZIP 32 path. That is a
 * deliberate v1 choice and the reason the encrypted record is custody-critical: a restored backup
 * cannot regenerate these.
 */
interface GiftKeyProvider {
    /** Generates a fresh 24-word phrase and its unified address. Entirely offline. */
    suspend fun mint(network: ZcashNetwork): EphemeralGiftKeys

    /**
     * Re-derives the unified address a phrase produces, so a claim can check the address in a link
     * against the link's own mnemonic before it acts on either.
     */
    suspend fun deriveAddress(mnemonic: String, network: ZcashNetwork): String
}

internal class GiftKeyProviderImpl : GiftKeyProvider {
    override suspend fun mint(network: ZcashNetwork): EphemeralGiftKeys =
        withContext(Dispatchers.IO) {
            val code = Mnemonics.MnemonicCode(Mnemonics.WordCount.COUNT_24.toEntropy())
            val phrase =
                try {
                    SeedPhrase(code.words.map { it.concatToString() })
                } finally {
                    // Zeroes the backing char array.
                    code.close()
                }
            EphemeralGiftKeys(mnemonic = phrase.joinToString(), address = deriveAddress(phrase.joinToString(), network))
        }

    override suspend fun deriveAddress(mnemonic: String, network: ZcashNetwork): String =
        withContext(Dispatchers.IO) {
            val seed = SeedPhrase.new(mnemonic).toByteArray()
            try {
                // Unlike iOS, Android derives the address straight from the seed with no separate
                // UFVK step. Account index 0: the wallet is new, so it holds exactly one account.
                DerivationTool.getInstance().deriveUnifiedAddress(
                    seed = seed,
                    network = network,
                    accountIndex = Zip32AccountIndex.new(0)
                )
            } finally {
                seed.fill(0)
            }
        }
}
