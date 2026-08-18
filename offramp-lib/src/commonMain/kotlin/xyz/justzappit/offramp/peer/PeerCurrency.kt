// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import kotlinx.serialization.Serializable
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex

/**
 * The oracle-priceable subset of the currencies Peer lists on chain. A currency is sellable only if
 * it has a Chainlink feed on Base, so the ones without a feed are absent by construction rather
 * than filtered later.
 *
 * Deliberately not an extension of `CurrencyCode`: that enum is the p2p.me corridor set and drives
 * QR parsers and corridor flags, so adding EUR there would leak Peer currencies into flows that
 * cannot serve them.
 *
 * [invert] means the feed prices CUR/USD, so fiat per USDC is `1e8 / answer`.
 */
@Serializable
enum class PeerCurrency(
    val code: String,
    val feedHex: String?,
    val invert: Boolean,
    val precision: Int,
    val symbol: String,
) {
    USD("USD", null, false, 2, "$"),
    EUR("EUR", "0xc91D87E81faB8f93699ECf7Ee9B44D11e1D53F0F", true, 2, "€"),
    GBP("GBP", "0xCceA6576904C118037695eB71195a5425E69Fa15", true, 2, "£"),
    AUD("AUD", "0x46e51B8cA41d709928EdA9Ae43e42193E6CDf229", true, 2, "A$"),
    CAD("CAD", "0xA840145F87572E82519d578b1F36340368a25D5d", true, 2, "C$"),
    CHF("CHF", "0x3A1d6444fb6a402470098E23DaD0B7E86E14252F", true, 2, "CHF"),
    MXN("MXN", "0x9e8Ee77c76d4fa41306056D1C3196AF5da1600bd", true, 2, "MX$"),
    NZD("NZD", "0x06bdFe07E71C476157FC025d3cCD4BBe08e83EF9", true, 2, "NZ$"),
    SGD("SGD", "0x81575495532fB311Efc5C993B612564274F0949b", true, 2, "S$"),
    TRY("TRY", "0x29413773e7CD4Dfd6Ad89a50887877b88a6C592C", true, 2, "₺"),
    ZAR("ZAR", "0x2ecc8A8B370fC6a217166b2782a35339bEBEe98B", true, 2, "R"),
    ;

    val codeHash: CurrencyHash = CurrencyHash.parse(keccak256(code.encodeToByteArray()).toHex())

    /** Null for USD, which is 1:1 with USDC and short-circuits before any feed read. */
    val feed: Address? = feedHex?.let(Address::parse)

    override fun toString(): String = code

    companion object {
        const val FEED_DECIMALS: Int = 8

        fun fromCodeOrNull(code: String): PeerCurrency? {
            val normalised = code.uppercase()
            return entries.firstOrNull { it.code == normalised }
        }

        /** Resolves the indexer's `currencyCode` field, which is the keccak hash, not the ticker. */
        fun fromHashOrNull(hash: String): PeerCurrency? {
            val normalised = CurrencyHash.parse(hash)
            return entries.firstOrNull { it.codeHash == normalised }
        }
    }
}

/**
 * The currencies an order accepts, with the one the rate and the market note are quoted in held as
 * a field rather than read back out of a `Set`. A set has no ordering contract, so `firstOrNull()`
 * on one that has been through `+` and `-` is how a GBP rate ends up labelled EUR.
 */
data class PeerCurrencySelection(
    val primary: PeerCurrency,
    val additional: List<PeerCurrency> = emptyList(),
) {
    init {
        require(primary !in additional) { "primary must not repeat in additional" }
        require(additional.size == additional.toSet().size) { "currencies must be unique" }
    }

    val all: List<PeerCurrency> get() = listOf(primary) + additional

    operator fun contains(currency: PeerCurrency): Boolean = currency == primary || currency in additional

    /**
     * Adds, or removes and promotes. Removing the last one is refused: a deposit with no currency
     * cannot be filled by anyone.
     */
    fun toggle(currency: PeerCurrency): PeerCurrencySelection =
        when {
            currency == primary && additional.isEmpty() -> {
                this
            }

            currency == primary -> {
                PeerCurrencySelection(primary = additional.first(), additional = additional.drop(1))
            }

            currency in additional -> {
                copy(additional = additional - currency)
            }

            else -> {
                copy(additional = additional + currency)
            }
        }

    companion object {
        /** Iteration order is the caller's declaration order, which is where a primary is chosen. */
        fun of(currencies: Collection<PeerCurrency>): PeerCurrencySelection {
            val ordered = currencies.distinct()
            require(ordered.isNotEmpty()) { "pick at least one currency" }
            return PeerCurrencySelection(primary = ordered.first(), additional = ordered.drop(1))
        }
    }
}
