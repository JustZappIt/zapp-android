// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.onramp.OnrampCheckpoint
import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampPhase
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OnrampZecDeliveryCheckpointStoreTest {
    @Test
    fun `delivery update preserves the completed P2P checkpoint`() =
        runTest {
            val storage = FakeOnrampCheckpointStorage(parent())
            val delivery = delivery()

            OnrampZecDeliveryCheckpointStoreImpl(storage).save(ORDER_ID, delivery)

            assertEquals(parent().copy(zecDelivery = delivery), storage.get())
        }

    @Test
    fun `delivery update rejects another order`() =
        runTest {
            val storage = FakeOnrampCheckpointStorage(parent())

            assertFailsWith<IllegalArgumentException> {
                OnrampZecDeliveryCheckpointStoreImpl(storage).save("another-order", delivery())
            }

            assertEquals(parent(), storage.get())
        }

    private companion object {
        const val ORDER_ID = "onramp-order"
        const val ACCOUNT = "0x0000000000000000000000000000000000000001"

        fun parent() =
            OnrampCheckpoint(
                id = ORDER_ID,
                phase = OnrampPhase.COMPLETED,
                orderId = "659007",
                destination = OnrampDestination.ZCASH,
            )

        fun delivery() =
            OnrampZecDeliveryCheckpoint(
                phase = OnrampZecDeliveryPhase.FUNDS_ON_BASE,
                usdcMicros = "910153",
                baseAccount = ACCOUNT,
            )
    }
}
