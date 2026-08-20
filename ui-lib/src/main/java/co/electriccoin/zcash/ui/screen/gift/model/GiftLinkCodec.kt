// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift.model

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

/**
 * Host serving gift links.
 *
 * TODO [#0]: confirm the production host and stand up DNS before shipping. `gift.justzappit.xyz` is
 * a working assumption; the manifest App Links filter and `assetlinks.json` must use the same name.
 */
const val GIFT_LINK_HOST = "gift.justzappit.xyz"

/** Why a link was rejected. Each case is a distinct thing to tell the recipient. */
enum class GiftLinkError {
    /** Over [GiftLinkCodec.MAX_URI_BYTES], by character count or by UTF-8 byte size. */
    TOO_LARGE,

    /** Not a gift link at all: wrong scheme, host or path, or no usable fragment. */
    MALFORMED_URI,

    /** The fragment did not decode to JSON we understand — bad base64, bad JSON, or unknown fields. */
    MALFORMED_PAYLOAD,

    /** A link version this build does not know how to claim. */
    UNSUPPORTED_VERSION,

    /** A mainnet card on a testnet wallet, or the reverse. */
    NETWORK_MISMATCH,

    INVALID_ADDRESS,

    /** The address in the link is not the one its mnemonic derives — the link has been tampered with. */
    ADDRESS_MISMATCH,

    INVALID_AMOUNT,

    INVALID_MNEMONIC,

    /** Non-positive, or below the network's Sapling activation. */
    INVALID_BIRTHDAY,

    /** Claims to have been created above the current chain tip. */
    BIRTHDAY_ABOVE_TIP,

    INVALID_CREATED_AT,

    MESSAGE_TOO_LONG,
}

/** A link we refuse to act on. Carries [error] so the UI can say which check failed. */
class GiftLinkException(
    val error: GiftLinkError,
) : RuntimeException(error.name)

/** What scanning back to a card's birthday would cost the recipient. */
sealed interface GiftBirthdayVerdict {
    /** Recent enough to scan without asking. */
    data object Proceed : GiftBirthdayVerdict

    /** Far enough back that the recipient must opt into the scan. */
    data class NeedsConsent(
        val blocksToScan: Long,
    ) : GiftBirthdayVerdict
}

/**
 * Encodes and decodes gift links.
 *
 * Pure — no network, no key derivation, no Android framework — so every rule here is unit-testable
 * on the JVM. The one check that needs derivation, address-matches-mnemonic, is
 * [verifyAddressMatches], which takes the derived address as an argument.
 *
 * The bearer secret rides in the fragment rather than the query because everything after `#` is
 * never put on the wire by an HTTP client: it reaches no server, proxy, `Referer` header or
 * link-preview crawler. It costs nothing at intake either, since Android intent filters cannot
 * match on a fragment and never strip one.
 *
 * Never log a URI, a payload or a mnemonic from here, at any level, including error paths.
 */
object GiftLinkCodec {
    const val VERSION = 1

    /** Bound on anything we will even attempt to decode. */
    const val MAX_URI_BYTES = 16 * 1024

    /** How far back a birthday may sit before the recipient has to consent to the scan. */
    const val SILENT_SCAN_BLOCKS = 100_000L

    private const val SCHEME = "https"
    private const val LINK_PATH = "/c/v1"
    private const val FRAGMENT_PREFIX = "k="
    private const val MNEMONIC_WORD_COUNT = 24
    private const val NETWORK_MAIN = "main"
    private const val NETWORK_TEST = "test"

    private val WHITESPACE = Regex("\\s+")

    // No ignoreUnknownKeys: an unrecognised field means the link came from something we do not
    // understand, and this one carries spendable money. (EncryptedJsonStore sets it — a different
    // contract, over data that is already ours and already on disk.)
    private val json = Json { explicitNulls = false }

    // Unpadded on encode, tolerant of padding on decode.
    @OptIn(ExperimentalEncodingApi::class)
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /** The link's name for [network], or null for a network gift cards do not support. */
    fun networkName(network: ZcashNetwork): String? =
        when {
            network.isMainnet() -> NETWORK_MAIN
            network.isTestnet() -> NETWORK_TEST
            else -> null
        }

    /**
     * Builds the shareable link, validating first: a link we would refuse to decode is a card whose
     * funds nobody can ever reach.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun encode(payload: GiftLinkPayload): String {
        val normalized = payload.normalized()
        validateShape(normalized)
        val body = base64.encode(json.encodeToString(GiftLinkPayload.serializer(), normalized).toByteArray())
        return "$SCHEME://$GIFT_LINK_HOST$LINK_PATH#$FRAGMENT_PREFIX$body"
    }

    /**
     * Parses and validates a link without touching the network.
     *
     * Does not verify that [GiftLinkPayload.address] is the address its own mnemonic derives — that
     * needs key derivation, so callers must follow up with [verifyAddressMatches].
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(uri: String, walletNetwork: ZcashNetwork): GiftLinkPayload {
        // Both bounds, in this order: length is UTF-16 units and cannot bound the byte size, while
        // measuring bytes first would mean copying whatever we were handed.
        ensure(uri.length <= MAX_URI_BYTES && uri.toByteArray().size <= MAX_URI_BYTES, GiftLinkError.TOO_LARGE)

        val fragment = parseFragment(uri)
        val decoded =
            try {
                base64.decode(fragment).decodeToString()
            } catch (_: IllegalArgumentException) {
                throw GiftLinkException(GiftLinkError.MALFORMED_PAYLOAD)
            }
        val payload =
            try {
                // Neither chain the cause nor quote the message: kotlinx embeds a snippet of the
                // input it failed on, which here is the bearer mnemonic.
                json.decodeFromString(GiftLinkPayload.serializer(), decoded).normalized()
            } catch (_: IllegalArgumentException) {
                throw GiftLinkException(GiftLinkError.MALFORMED_PAYLOAD)
            }

        validateShape(payload)
        ensure(payload.network == networkName(walletNetwork), GiftLinkError.NETWORK_MISMATCH)
        return payload
    }

    /**
     * Confirms the link's address is the one its own mnemonic produces. Callers derive the address
     * from [GiftLinkPayload.mnemonic] and pass it in.
     */
    fun verifyAddressMatches(payload: GiftLinkPayload, derivedAddress: String) {
        ensure(payload.address == derivedAddress.trim(), GiftLinkError.ADDRESS_MISMATCH)
    }

    /**
     * Decides what scanning back to a card's birthday costs, given the current chain tip.
     *
     * Deliberately does not clamp. A note is only found by trial-decrypting the block that carries
     * it, so a birthday clamped past the funding height finds nothing and the claim fails on a
     * perfectly good card. With no reclaim that burns the funds, and it quietly reinstates the hard
     * expiry the design rejects. Bound the scan by asking the recipient, never by moving the height.
     */
    fun evaluateBirthday(birthdayHeight: Long, chainTip: Long): GiftBirthdayVerdict {
        ensure(birthdayHeight <= chainTip, GiftLinkError.BIRTHDAY_ABOVE_TIP)
        return if (birthdayHeight >= chainTip - SILENT_SCAN_BLOCKS) {
            GiftBirthdayVerdict.Proceed
        } else {
            GiftBirthdayVerdict.NeedsConsent(chainTip - birthdayHeight)
        }
    }

    private fun parseFragment(uri: String): String {
        val parsed =
            try {
                URI(uri)
            } catch (_: URISyntaxException) {
                throw GiftLinkException(GiftLinkError.MALFORMED_URI)
            }
        ensure(SCHEME.equals(parsed.scheme, ignoreCase = true), GiftLinkError.MALFORMED_URI)
        ensure(GIFT_LINK_HOST.equals(parsed.host, ignoreCase = true), GiftLinkError.MALFORMED_URI)
        ensure(parsed.path == LINK_PATH, GiftLinkError.MALFORMED_URI)
        // A gift link never carries a query. One here means something rewrote the link, and
        // whatever it wrote was seen by every server on the way.
        ensure(parsed.rawQuery == null, GiftLinkError.MALFORMED_URI)
        val fragment = parsed.rawFragment.orEmpty()
        ensure(fragment.startsWith(FRAGMENT_PREFIX), GiftLinkError.MALFORMED_URI)
        return fragment.removePrefix(FRAGMENT_PREFIX)
    }

    private fun validateShape(payload: GiftLinkPayload) {
        ensure(payload.v == VERSION, GiftLinkError.UNSUPPORTED_VERSION)

        val network =
            when (payload.network) {
                NETWORK_MAIN -> ZcashNetwork.Mainnet
                NETWORK_TEST -> ZcashNetwork.Testnet
                else -> throw GiftLinkException(GiftLinkError.NETWORK_MISMATCH)
            }

        ensure(payload.address.isNotEmpty(), GiftLinkError.INVALID_ADDRESS)

        val amount = payload.amountZatoshi.toLongOrNull()
        ensure(amount != null && amount > 0 && amount <= Zatoshi.MAX_INCLUSIVE, GiftLinkError.INVALID_AMOUNT)

        validateMnemonic(payload.mnemonic)

        ensure(
            payload.birthdayHeight > 0 && payload.birthdayHeight >= network.saplingActivationHeight.value,
            GiftLinkError.INVALID_BIRTHDAY
        )

        ensure(runCatching { Instant.parse(payload.createdAt) }.isSuccess, GiftLinkError.INVALID_CREATED_AT)

        // expiresAt is deliberately not rejected when unparseable. It is advisory — nothing on
        // chain enforces it — so a peer's clock or formatting must never become the reason a funded
        // card cannot be claimed. Readers treat what they cannot parse as absent.

        payload.message?.let { ensure(GiftMessage.isWithinLimits(it), GiftLinkError.MESSAGE_TOO_LONG) }
    }

    private fun validateMnemonic(mnemonic: String) {
        val code = Mnemonics.MnemonicCode(mnemonic)
        try {
            // validate() throws InvalidWordException, ChecksumException or WordCountException,
            // three RuntimeExceptions with no common supertype and one meaning here.
            ensure(
                code.wordCount == MNEMONIC_WORD_COUNT && runCatching { code.validate() }.isSuccess,
                GiftLinkError.INVALID_MNEMONIC
            )
        } finally {
            // Zeroes the backing char array. The phrase is the money.
            code.close()
        }
    }

    private fun GiftLinkPayload.normalized() =
        copy(
            network = network.trim(),
            address = address.trim(),
            amountZatoshi = amountZatoshi.trim(),
            // Collapse whitespace runs as well as trimming: a phrase that survived a copy-paste
            // through a chat client is still the same 24 words, and BIP-39 wants them single-spaced.
            mnemonic = mnemonic.trim().split(WHITESPACE).joinToString(" "),
            createdAt = createdAt.trim(),
            expiresAt = expiresAt?.trim(),
            message = message?.trim(),
        )

    private fun ensure(condition: Boolean, error: GiftLinkError) {
        if (!condition) throw GiftLinkException(error)
    }
}
