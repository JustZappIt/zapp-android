// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.provider.ZcashNetworkProvider
import co.electriccoin.zcash.ui.common.usecase.GetWalletSeedBytesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.atomicswap.Deployment
import xyz.justzappit.atomicswap.SwapKey
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.AtomicSwapKeys
import xyz.justzappit.offramp.atomicswap.PayoutNote
import xyz.justzappit.offramp.atomicswap.UserAcceptance

/** [AtomicSwapKeys] over libzecswap, with the wallet's seed read for each call and wiped after. */
class AtomicSwapKeysImpl(
    private val getWalletSeedBytes: GetWalletSeedBytesUseCase,
    private val zcashNetworkProvider: ZcashNetworkProvider,
) : AtomicSwapKeys {
    override suspend fun userShare(index: Int) = withKey(index) { AtomicSwap.userShare(it) }

    override suspend fun authAddress(index: Int) = withKey(index) { Address.fromBytes(AtomicSwap.authAddress(it)) }

    override suspend fun payoutNote(index: Int) =
        withKey(index) {
            val note = AtomicSwap.payoutNote(it)
            PayoutNote(note.npk, note.encryptedBundle, note.shieldKey, note.commitment)
        }

    override suspend fun accept(
        index: Int,
        chainId: Long,
        contract: Address,
        quoteId: ByteArray,
        makerShare: ByteArray,
        makerProof: ByteArray,
    ) = withKey(index) {
        val acceptance = AtomicSwap.accept(it, Deployment(chainId, contract.bytes), quoteId, makerShare, makerProof)
        UserAcceptance(acceptance.userShare, acceptance.userProof, acceptance.viewingKeys)
    }

    override suspend fun depositAddress(
        index: Int,
        makerShare: ByteArray
    ) = withKey(index) { AtomicSwap.depositAccount(it, makerShare).address }

    override suspend fun claimSecret(index: Int) = withKey(index) { AtomicSwap.claimSecret(it) }

    override suspend fun signLockClaim(
        index: Int,
        chainId: Long,
        contract: Address,
        swapId: ByteArray,
        deadline: Long,
    ) = withKey(index) { AtomicSwap.signLockClaim(it, Deployment(chainId, contract.bytes), swapId, deadline) }

    override suspend fun signPayout(
        index: Int,
        chainId: Long,
        contract: Address,
        swapId: ByteArray,
        relayer: Address,
        fee: BigInteger,
    ) = withKey(index) {
        AtomicSwap.signPayout(it, Deployment(chainId, contract.bytes), swapId, relayer.bytes, fee)
    }

    /** Runs [block] with swap [index]'s key and zeroes the seed afterwards. */
    suspend fun <T> withKey(
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
}
