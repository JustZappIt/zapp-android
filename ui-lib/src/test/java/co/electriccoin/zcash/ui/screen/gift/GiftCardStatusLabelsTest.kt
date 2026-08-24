// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import co.electriccoin.zcash.ui.R
import kotlin.test.Test
import kotlin.test.assertEquals

class GiftCardStatusLabelsTest {
    @Test
    fun `shared card is not called unclaimed before a check`() {
        assertEquals(
            R.string.gift_card_chip_shared_unchecked,
            GiftCardListStatus.SHARED.chipRes(hasBeenChecked = false, isCheckRecent = false),
        )
    }

    @Test
    fun `shared card asks for another check after evidence goes stale`() {
        assertEquals(
            R.string.gift_card_chip_shared_stale,
            GiftCardListStatus.SHARED.chipRes(hasBeenChecked = true, isCheckRecent = false),
        )
    }

    @Test
    fun `shared card is called unclaimed only after a recent check`() {
        assertEquals(
            R.string.gift_card_chip_shared,
            GiftCardListStatus.SHARED.chipRes(hasBeenChecked = true, isCheckRecent = true),
        )
    }
}
