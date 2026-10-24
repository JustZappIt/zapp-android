// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import xyz.justzappit.evm.deposits.DepositKey
import xyz.justzappit.evm.deposits.DepositNote
import xyz.justzappit.evm.types.Address

/** The wallet's deposit keys, derived from its seed ([xyz.justzappit.evm.deposits.DepositKeys]), lent one at a time. */
interface DepositKeyring {
    /** The deposit addresses at [indices], in order. */
    suspend fun addresses(indices: IntRange): List<Address>

    /** [block] with deposit address [index]'s key, wiped with the seed it came from once [block] returns. */
    suspend fun <T> withKey(
        index: Int,
        block: (DepositKey) -> T,
    ): T
}

/**
 * A new note for a deposit or a rescue to shield to, built to the wallet's own Railgun keys and opened as its receiver
 * would before it's given out. Each is new, and none can be built again: keep the request that pays it whole.
 */
fun interface DepositNotes {
    suspend fun note(): DepositNote
}

/** Where deposits are kept, each before anything posts it. A store that can't be read throws: it's never empty. */
interface DepositStore {
    /** Every deposit kept, newest first. */
    suspend fun records(): List<DepositRecord>

    /** Keeps [records] in one write, each in place of the one with its id: a request and what replaces it, together. */
    suspend fun keep(records: List<DepositRecord>)

    /** The last scan of the deposit addresses, if one was kept. */
    suspend fun scan(): DepositScan?

    suspend fun keepScan(scan: DepositScan)
}
