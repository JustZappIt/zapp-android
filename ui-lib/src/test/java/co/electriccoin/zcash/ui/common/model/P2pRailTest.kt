package co.electriccoin.zcash.ui.common.model

import org.junit.Test
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.peer.PeerPlatform
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The preference key still holds values written before Peer existed, when it was a bare currency
 * code. Failing to read one resets the user's rail to the default without saying so.
 */
class P2pRailTest {
    @Test
    fun `a selection written before Peer existed still resolves`() {
        assertEquals(P2pRail.ScanAndPay(CurrencyCode.Inr), P2pRail.fromIdOrNull("INR"))
    }

    @Test
    fun `every rail round-trips through its stored id`() {
        val rails =
            CurrencyCode.entries.map { P2pRail.ScanAndPay(it) } +
                PeerPlatform.entries.map { P2pRail.PeerCashOut(it) }

        rails.forEach { rail ->
            assertEquals(rail, P2pRail.fromIdOrNull(rail.id), "${rail.id} must round-trip")
        }
    }

    @Test
    fun `ids are unique across providers so one selection cannot read as another`() {
        val ids =
            (
                CurrencyCode.entries.map { P2pRail.ScanAndPay(it).id } +
                    PeerPlatform.entries.map { P2pRail.PeerCashOut(it).id }
            )

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `an unrecognised value is refused rather than coerced`() {
        assertNull(P2pRail.fromIdOrNull("ZZZ"))
        assertNull(P2pRail.fromIdOrNull("peer:monopoly"))
        assertNull(P2pRail.fromIdOrNull("p2pme:ZZZ"))
        assertNull(P2pRail.fromIdOrNull(""))
    }

    /** The default is the fallback for the flows only p2p.me can serve, so its type is load-bearing. */
    @Test
    fun `the default is a scan-and-pay rail`() {
        assertEquals(CurrencyCode.Inr, P2pRail.DEFAULT.currency)
        assertEquals(P2pProvider.P2P_ME, P2pRail.DEFAULT.provider)
    }
}
