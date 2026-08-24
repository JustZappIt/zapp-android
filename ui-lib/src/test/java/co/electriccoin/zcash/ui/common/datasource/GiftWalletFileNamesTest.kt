// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.datasource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the SDK-internal layout a claimed card's wallet is deleted by, since `Synchronizer.erase`
 * cannot be used — see `GiftClaimDataSourceImpl.deleteWallet`. `DatabaseCoordinator` builds these
 * names from `<alias>_<networkName>_` plus `DB_DATA_NAME` / `DB_FS_BLOCK_DB_ROOT_NAME`, with
 * `-journal` and `-wal` alongside the database.
 */
class GiftWalletFileNamesTest {
    @Test
    fun `names every file DatabaseCoordinator would have created`() {
        assertEquals(
            listOf(
                "gift_abc_mainnet_fs_cache",
                "gift_abc_mainnet_data.sqlite3",
                "gift_abc_mainnet_data.sqlite3-journal",
                "gift_abc_mainnet_data.sqlite3-wal",
                "gift_abc_mainnet_data.sqlite3-shm",
            ),
            giftWalletFileNames(alias = "gift_abc", networkName = "mainnet"),
        )
    }

    @Test
    fun `keeps one card's files clear of another's`() {
        val mine = giftWalletFileNames(alias = "gift_aa", networkName = "testnet")
        val theirs = giftWalletFileNames(alias = "gift_ab", networkName = "testnet")

        assertTrue(mine.none { it in theirs })
        // A prefix match would sweep gift_aa's files while deleting gift_a's.
        assertTrue(mine.none { name -> theirs.any { name.startsWith(it) } })
    }
}
