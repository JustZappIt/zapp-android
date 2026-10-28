// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapDeployments
import co.electriccoin.zcash.ui.common.provider.railgunSeed
import co.electriccoin.zcash.ui.common.usecase.GetWalletSeedBytesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.atomicswap.DepositShieldNote
import xyz.justzappit.atomicswap.RailgunSeed
import xyz.justzappit.evm.deposits.DepositNote
import xyz.justzappit.offramp.deposits.DepositNotes

/**
 * The notes deposits shield to, built in libzecswap from the Zcash seed ([AtomicSwap.shieldNote] and
 * [AtomicSwap.rescueNote]): deposit address [index]'s keys, on the network and ZIP 32 account
 * [PrivateUsdDepositKeyring] derives them for, and the Railgun wallet's seed, BIP-85's child of the Zcash seed, whose
 * own viewing key opens each note before it's given out. Nothing here trusts the Railgun page for a note's receiver,
 * and the note of a kept request builds again from the same inputs. Both seeds are read for each note and wiped after.
 */
class PrivateUsdDepositNotes(
    private val getWalletSeedBytes: GetWalletSeedBytesUseCase,
    private val deployments: AtomicSwapDeployments,
) : DepositNotes {
    override suspend fun shield(
        index: Int,
        validBefore: Long,
    ): DepositNote =
        note { seed, railgun ->
            AtomicSwap.shieldNote(seed, deployments.depositsOnMainnet(), DEPOSIT_ACCOUNT, index, validBefore, railgun)
        }

    override suspend fun rescue(
        index: Int,
        nonce: Long,
    ): DepositNote =
        note { seed, railgun ->
            AtomicSwap.rescueNote(seed, deployments.depositsOnMainnet(), DEPOSIT_ACCOUNT, index, nonce, railgun)
        }

    private suspend fun note(build: (ByteArray, RailgunSeed) -> DepositShieldNote): DepositNote =
        withContext(Dispatchers.Default) {
            val seed = getWalletSeedBytes()
            try {
                val railgun = railgunSeed(seed)
                try {
                    build(seed, RailgunSeed(railgun))
                } finally {
                    railgun.fill(0)
                }
            } finally {
                seed.fill(0)
            }
        }.checked()
}

// A note is kept and signed for by its five words alone: what they commit to here must be what libzecswap says.
private fun DepositShieldNote.checked(): DepositNote {
    val note = DepositNote(npk, encryptedBundle, shieldKey)
    check(note.commitment.contentEquals(commitment)) { "a deposit note's words don't commit to what libzecswap says" }
    return note
}
