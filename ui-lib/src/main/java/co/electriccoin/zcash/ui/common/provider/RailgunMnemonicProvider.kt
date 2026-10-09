// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.ui.common.usecase.GetWalletSeedBytesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.atomicswap.RailgunSeed
import xyz.justzappit.evm.hd.Bip85
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.railgun.RailgunAddress
import java.security.MessageDigest

/**
 * The Railgun wallet's own mnemonic: BIP-85's 24 English words at index 0 of the Zcash seed. The WebView
 * running Railgun's SDK only ever gets these words, and the Zcash seed alone still restores them.
 */
interface RailgunMnemonicProvider {
    /** Emits for the current Zcash wallet, then again each time another replaces it. */
    val walletChanges: Flow<Unit>

    /** Runs [block] with the words, separated by spaces, and wipes them after. */
    suspend fun <T> withMnemonic(block: suspend (CharArray) -> T): T

    /** The wallet's `0zk` address, where conversions pay out and refund. */
    suspend fun address(): RailgunAddress
}

class RailgunMnemonicProviderImpl(
    private val getWalletSeedBytes: GetWalletSeedBytesUseCase,
    persistableWalletProvider: PersistableWalletProvider,
) : RailgunMnemonicProvider {
    override val walletChanges: Flow<Unit> =
        persistableWalletProvider.persistableWallet
            .map { it?.fingerprint() }
            .distinctUntilChanged()
            .map { }

    override suspend fun <T> withMnemonic(block: suspend (CharArray) -> T): T =
        fromZcashSeed(::railgunMnemonic).use { block(it.chars) }

    override suspend fun address(): RailgunAddress =
        fromZcashSeed { zcashSeed ->
            val seed = railgunSeed(zcashSeed)
            try {
                RailgunAddress(AtomicSwap.railgunAddress(RailgunSeed(seed)))
            } finally {
                seed.fill(0)
            }
        }

    private suspend fun <T> fromZcashSeed(derive: (ByteArray) -> T): T =
        withContext(Dispatchers.Default) {
            val zcashSeed = getWalletSeedBytes()
            try {
                derive(zcashSeed)
            } finally {
                zcashSeed.fill(0)
            }
        }
}

/** Tells this wallet from another without keeping its words: a hash of them. */
internal fun PersistableWallet.fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(seedPhrase.joinToString().encodeToByteArray()).toHex()

/** The Railgun mnemonic of the Zcash wallet whose 64-byte BIP-39 seed is [zcashSeed]; closing it wipes it. */
internal fun railgunMnemonic(zcashSeed: ByteArray): Mnemonics.MnemonicCode {
    val entropy = Bip85.bip39Entropy(zcashSeed, Mnemonics.WordCount.COUNT_24.count, index = 0)
    return try {
        Mnemonics.MnemonicCode(entropy)
    } finally {
        entropy.fill(0)
    }
}

/** The 64-byte BIP-39 seed of [zcashSeed]'s Railgun mnemonic, which the Railgun wallet's keys derive from. */
internal fun railgunSeed(zcashSeed: ByteArray): ByteArray = railgunMnemonic(zcashSeed).use { it.toSeed() }
