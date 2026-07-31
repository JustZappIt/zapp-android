package co.electriccoin.zcash.ui.common.provider

import xyz.justzappit.offramp.account.SeedPhraseSource

/**
 * Bridges the wallet's stored mnemonic to offramp-lib's [SeedPhraseSource] so the mainnet
 * [xyz.justzappit.offramp.account.StaticOfframpAccountProvider] can derive the ERC-4337 owner key
 * from the user's own seed (self-custodial), instead of the committed testnet dev key.
 *
 * Returns a fresh, zero-fillable [CharArray] — the offramp's PBKDF2 runs in-process and clears
 * its `PBEKeySpec` inputs, so wiping the buffer at this seam actually closes a window. The
 * individual word `String`s in the wallet's `List<String>` remain immutable (not under our
 * control), but at least we don't pin a joined `String` for the GC to chew through.
 */
class WalletSeedPhraseSource(
    private val persistableWalletProvider: PersistableWalletProvider,
) : SeedPhraseSource {
    override suspend fun getSeedPhrase(): CharArray {
        val words = persistableWalletProvider.requirePersistableWallet().seedPhrase.split
        if (words.isEmpty()) return CharArray(0)
        val total = words.sumOf { it.length } + (words.size - 1)
        val out = CharArray(total)
        var pos = 0
        for ((i, word) in words.withIndex()) {
            if (i > 0) {
                out[pos] = ' '
                pos++
            }
            word.toCharArray(out, pos, 0, word.length)
            pos += word.length
        }
        return out
    }
}
