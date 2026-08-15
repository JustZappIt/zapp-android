package co.electriccoin.zcash.ui.common.model

import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.peer.PeerPlatform

/**
 * The two P2P products the app offers. They are different enough that a shared list without a
 * visible provider would let a user pick "cash out" expecting "scan and pay".
 */
enum class P2pProvider {
    P2P_ME,
    PEER,
}

/**
 * A selected P2P rail. Peer rails break the old one-currency-per-method mapping — a rail is a
 * platform that offers several currencies — so the identity of a selection is a typed id rather
 * than a currency code.
 */
sealed interface P2pRail {
    /** Stable persistence key. */
    val id: String

    val provider: P2pProvider

    /** p2p.me: scan a merchant QR and pay in one currency. */
    data class ScanAndPay(
        val currency: CurrencyCode,
    ) : P2pRail {
        override val id: String get() = SCAN_AND_PAY_PREFIX + currency.code
        override val provider: P2pProvider get() = P2pProvider.P2P_ME
    }

    /** Peer: offer USDC to buyers and receive fiat into your own account. */
    data class PeerCashOut(
        val platform: PeerPlatform,
    ) : P2pRail {
        override val id: String get() = PEER_PREFIX + platform.wireName
        override val provider: P2pProvider get() = P2pProvider.PEER
    }

    companion object {
        const val SCAN_AND_PAY_PREFIX = "p2pme:"
        const val PEER_PREFIX = "peer:"

        /** Typed narrowly: the flows that only p2p.me can serve fall back to it without a cast. */
        val DEFAULT: ScanAndPay = ScanAndPay(CurrencyCode.Inr)

        /**
         * An unprefixed value is a rail id written before Peer existed, when the preference held a
         * bare currency code. It resolves rather than resetting the user's choice, and is rewritten
         * in the new form on the next save.
         */
        fun fromIdOrNull(raw: String): P2pRail? =
            when {
                raw.startsWith(SCAN_AND_PAY_PREFIX) -> {
                    CurrencyCode.fromCodeOrNull(raw.removePrefix(SCAN_AND_PAY_PREFIX))?.let(::ScanAndPay)
                }

                raw.startsWith(PEER_PREFIX) -> {
                    PeerPlatform.fromWireNameOrNull(raw.removePrefix(PEER_PREFIX))?.let(::PeerCashOut)
                }

                else -> {
                    CurrencyCode.fromCodeOrNull(raw)?.let(::ScanAndPay)
                }
            }
    }
}
