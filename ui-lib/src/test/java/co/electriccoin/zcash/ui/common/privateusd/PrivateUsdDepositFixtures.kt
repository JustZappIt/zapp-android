// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.SwapNote
import xyz.justzappit.offramp.deposits.DepositKind
import xyz.justzappit.offramp.deposits.DepositLanding
import xyz.justzappit.offramp.deposits.DepositRecord
import xyz.justzappit.offramp.deposits.DepositState
import xyz.justzappit.offramp.deposits.RescueDeposit
import xyz.justzappit.offramp.deposits.ShieldDeposit
import xyz.justzappit.offramp.deposits.SignedDepositTerms
import xyz.justzappit.offramp.p2p.Usdc6

/** The testnet's deposits pin. */
internal val TESTNET_DEPOSITS = checkNotNull(AtomicSwapTestnet.deployment.usdcDeposits)

/** A deposit address of no one's: deposits name one, never the wallet's real ones. */
internal fun depositAddress(index: Int): Address = Address.fromBytes(ByteArray(20) { (0x40 + index).toByte() })

internal fun depositTx(byte: Int) = TxHash(ByteArray(32) { byte.toByte() })

/**
 * A kept deposit of [value] base units, paying [fee], as a pass leaves it: a shield of address [index], or with
 * [kind] a rescue of its vault.
 */
internal fun depositRecord(
    index: Int = 0,
    state: DepositState = DepositState.SIGNED,
    createdAt: Long = 1_792_800_000,
    value: Long = 5_000_000,
    fee: Long = 105_000,
    kind: DepositKind = DepositKind.SHIELD,
    waiting: Boolean = false,
    transactions: List<TxHash> = emptyList(),
    landing: DepositLanding? = null,
    endedAt: Long? = landing?.at,
): DepositRecord {
    val note = SwapNote(WORD, List(3) { WORD }, WORD)
    val from = depositAddress(index)
    val chainId = TESTNET_DEPOSITS.chainId
    val contract = TESTNET_DEPOSITS.contract
    return DepositRecord(
        id = "${kind.name.lowercase()}-$index-$createdAt",
        kind = kind,
        index = index,
        state = state,
        createdAt = createdAt,
        value = Usdc6.ofMicros(value),
        terms = SignedDepositTerms(Usdc6.ofMicros(100_000), 10, Usdc6.ofMicros(2_000_000), 86_400),
        shield =
            ShieldDeposit(
                chainId,
                contract,
                from,
                Usdc6.ofMicros(value),
                0,
                createdAt + 3_600,
                note,
                Usdc6.ofMicros(fee),
                SIGNATURE,
            ).takeIf { kind == DepositKind.SHIELD },
        rescue =
            RescueDeposit(chainId, contract, from, note, Usdc6.ofMicros(fee), 0, createdAt + 3_600, SIGNATURE)
                .takeIf { kind == DepositKind.RESCUE },
        waiting = waiting,
        transactions = transactions,
        landing = landing,
        endedAt = endedAt,
    )
}

private val WORD = "0x" + "22".repeat(32)
private val SIGNATURE = "0x" + "11".repeat(65)
