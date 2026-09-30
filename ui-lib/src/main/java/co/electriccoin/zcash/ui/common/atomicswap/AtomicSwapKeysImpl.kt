// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
import co.electriccoin.zcash.ui.common.provider.fingerprint
import co.electriccoin.zcash.ui.common.provider.railgunSeed
import co.electriccoin.zcash.ui.common.usecase.GetWalletSeedBytesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.atomicswap.Deployment
import xyz.justzappit.atomicswap.RailgunSeed
import xyz.justzappit.atomicswap.SwapKey
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.atomicswap.AtomicSwapKeys
import xyz.justzappit.offramp.atomicswap.NoteCommitment
import xyz.justzappit.offramp.atomicswap.PayoutNote
import xyz.justzappit.offramp.atomicswap.RailgunKeySource
import xyz.justzappit.offramp.atomicswap.SwapId
import xyz.justzappit.offramp.atomicswap.SwapShare
import xyz.justzappit.offramp.atomicswap.UserAcceptance
import xyz.justzappit.offramp.p2p.Usdc6
import java.util.concurrent.ConcurrentHashMap

/** A swap index's key, lent to a block and wiped after: all that signing and the joint accounts need of the seed. */
interface SwapKeyring {
    suspend fun <T> withKey(
        index: Int,
        block: (SwapKey) -> T
    ): T

    /** [block] with swap [index]'s key and the Railgun seed [railgunKeys] names. */
    suspend fun <T> withKey(
        index: Int,
        railgunKeys: RailgunKeySource,
        block: (SwapKey, RailgunSeed) -> T
    ): T =
        withKey(index) { key ->
            val railgun =
                when (railgunKeys) {
                    RailgunKeySource.ZCASH_SEED -> key.seed.copyOf()
                    RailgunKeySource.BIP85 -> railgunSeed(key.seed)
                }
            try {
                block(key, RailgunSeed(railgun))
            } finally {
                railgun.fill(0)
            }
        }
}

/** The seed is read for each call and wiped after; only what a key shows anyone is kept, per wallet and index. */
class AtomicSwapKeysImpl(
    private val getWalletSeedBytes: GetWalletSeedBytesUseCase,
    persistableWalletProvider: PersistableWalletProvider,
    private val zcashNetworkProvider: ZcashNetworkProvider,
) : AtomicSwapKeys,
    SwapKeyring {
    private val shown = ShownKeysHolder(persistableWalletProvider)

    override suspend fun userShare(index: Int): SwapShare = public(index).share

    override suspend fun authAddress(index: Int): Address = public(index).address

    override suspend fun payoutNote(
        index: Int,
        railgunKeys: RailgunKeySource
    ): PayoutNote =
        shown().notes.cached(index to railgunKeys) {
            withKey(index, railgunKeys) { key, railgun ->
                val note = AtomicSwap.payoutNote(key, railgun)
                PayoutNote(note.npk, note.encryptedBundle, note.shieldKey, NoteCommitment.of(note.commitment))
            }
        }

    override suspend fun accept(
        index: Int,
        railgunKeys: RailgunKeySource,
        chainId: ChainId,
        contract: Address,
        quoteId: ByteArray,
        makerShare: SwapShare,
        makerProof: ByteArray,
    ) = withKey(index, railgunKeys) { key, railgun ->
        val acceptance =
            AtomicSwap.accept(key, railgun, domain(chainId, contract), quoteId, makerShare.bytes, makerProof)
        UserAcceptance(SwapShare.of(acceptance.userShare), acceptance.userProof, acceptance.viewingKeys)
    }

    override suspend fun depositAddress(
        index: Int,
        makerShare: SwapShare
    ): String =
        shown().depositAddresses.cached(index to makerShare) {
            withKey(index) { AtomicSwap.depositAccount(it, makerShare.bytes).address }
        }

    override suspend fun claimSecret(index: Int) = withKey(index) { AtomicSwap.claimSecret(it) }

    override suspend fun signLockClaim(
        index: Int,
        chainId: ChainId,
        contract: Address,
        swapId: SwapId,
        deadline: Long,
    ) = withKey(index) { AtomicSwap.signLockClaim(it, domain(chainId, contract), swapId.bytes, deadline) }

    override suspend fun signPayout(
        index: Int,
        chainId: ChainId,
        contract: Address,
        swapId: SwapId,
        relayer: Address,
        fee: Usdc6,
    ) = withKey(index) {
        AtomicSwap.signPayout(it, domain(chainId, contract), swapId.bytes, relayer.bytes, fee.micros)
    }

    override suspend fun <T> withKey(
        index: Int,
        block: (SwapKey) -> T
    ): T =
        withContext(Dispatchers.Default) {
            val seed = getWalletSeedBytes()
            try {
                block(SwapKey(seed, zcashNetworkProvider() == ZcashNetwork.Mainnet, index))
            } finally {
                seed.fill(0)
            }
        }

    private suspend fun public(index: Int): PublicKey =
        shown().keys.cached(index) {
            withKey(index) {
                PublicKey(SwapShare.of(AtomicSwap.userShare(it)), Address.fromBytes(AtomicSwap.authAddress(it)))
            }
        }

    private class PublicKey(
        val share: SwapShare,
        val address: Address,
    )

    // Kept for one wallet at a time: another wallet's indices are other keys.
    private class ShownKeysHolder(
        private val persistableWalletProvider: PersistableWalletProvider,
    ) {
        @Volatile
        private var shown: ShownKeys? = null

        suspend operator fun invoke(): ShownKeys {
            val wallet = persistableWalletProvider.requirePersistableWallet().fingerprint()
            return shown?.takeIf { it.wallet == wallet } ?: ShownKeys(wallet).also { shown = it }
        }
    }

    private class ShownKeys(
        val wallet: String
    ) {
        val keys = ConcurrentHashMap<Int, PublicKey>()
        val notes = ConcurrentHashMap<Pair<Int, RailgunKeySource>, PayoutNote>()
        val depositAddresses = ConcurrentHashMap<Pair<Int, SwapShare>, String>()
    }

    private companion object {
        suspend fun <K : Any, V : Any> ConcurrentHashMap<K, V>.cached(
            key: K,
            derive: suspend () -> V
        ): V = get(key) ?: derive().also { putIfAbsent(key, it) }
    }
}

/** The contract a signature is good for, as libzecswap names it. */
internal fun domain(
    chainId: ChainId,
    contract: Address
) = Deployment(chainId.value, contract.bytes)
