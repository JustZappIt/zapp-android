// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

@file:UseSerializers(LowercaseAddressSerializer::class)

package xyz.justzappit.offramp.atomicswap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6

/** A maker's quote, and a reverse quote's terms. The id and proof are `0x` hex, sent back as they came. */
@Serializable
data class SwapQuote(
    val quoteId: String,
    val maker: Address,
    val makerShare: SwapShare,
    val makerProof: String,
    val chainId: ChainId,
    val contract: Address,
    val token: Address,
    val amount: Usdc6,
    val depositZat: Long,
    val expiresAt: Long,
)

/** A quote accepted: our share, its proof, and the viewing keys the maker watches the deposit with. */
@Serializable
data class SwapAcceptance(
    val userShare: String,
    val userProof: String,
    val viewingKeys: String,
    /** On an accept a token pays for only: the blinded request for it back, base64url. */
    val tokenRequest: String? = null,
)

/** A lock or `ready` the swap's own key signed, for a relayer to send. */
@Serializable
data class SwapAuthorization(
    val swapId: SwapId,
    val deadline: Long,
    val signature: String,
)

/** The Railgun note a payout shields to. */
@Serializable
data class SwapNote(
    val npk: String,
    val encryptedBundle: List<String>,
    val shieldKey: String,
)

/** A payout into Railgun, paying the relayer at most [fee]. */
@Serializable
data class SwapPayout(
    val swapId: SwapId,
    val note: SwapNote,
    val fee: Usdc6,
    val signature: String,
)

/** A single approval to rescue returned funds, consumed by the contract and bounded in time. */
@Serializable
data class SwapRescue(
    val swapId: SwapId,
    val note: SwapNote,
    val fee: Usdc6,
    val nonce: Long,
    val deadline: Long,
    val signature: String,
)

/** A share revealed under a held lock, with the payout that follows it. */
@Serializable
data class SwapReveal(
    val swapId: SwapId,
    val secret: String,
    val payout: SwapPayout,
)

@Serializable
data class RelayerTerms(
    val relayer: Address,
    val chainId: ChainId,
    val contract: Address,
    val fee: Usdc6,
    val reverseFunding: ReverseFundingTerms? = null,
    /** Present when the relayer sends wallets' private Railgun sends and withdrawals, as their broadcaster. */
    val railgunSends: RailgunSendsTerms? = null,
)

/** How the relayer sends a wallet's Railgun transaction: it pays the gas for a fee note to [railgunAddress]. */
@Serializable
data class RailgunSendsTerms(
    /** The relayer's own 0zk address, which the fee note pays. */
    val railgunAddress: String,
    val railgunProxy: Address,
    val token: Address,
    val fee: Usdc6,
    val maxGasLimit: Long,
    /** In decimal wei: the highest minimum gas price a proof may bind. */
    val maxGasPriceWei: String,
    val maxCalldataBytes: Int,
)

/** A proved Railgun transaction for the relayer to send as it is. */
@Serializable
data class RailgunTransactRequest(
    val chainId: ChainId,
    val to: Address,
    val data: String,
    val value: String,
)

/** The Zcash network a maker takes deposits on. */
@Serializable
enum class SwapZcashNetwork {
    @SerialName("mainnet")
    MAINNET,

    @SerialName("testnet")
    TESTNET,
}

@Serializable
data class MakerInfo(
    val apiVersion: Int,
    val maker: Address,
    val chainId: ChainId,
    val contract: Address,
    val token: Address,
    val zcashNetwork: SwapZcashNetwork,
    val reverseEnabled: Boolean,
    /** The key accepts' tokens come back under, base64url SPKI; none where accepts take no tokens. */
    val tokenReturnKey: String? = null,
)

/** A maker's status of a swap either way, as far as the app reads it: its accept's token, once handed back. */
@Serializable
internal class MakerSwapStatus(
    val tokenReturn: String? = null,
)

/** The transactions a relayer request sent, in order. */
@Serializable
data class Sent(
    val transactions: List<TxHash>
)

@Serializable
internal data class QuoteRequest(
    val units: Long,
    val payout: Address,
    val payoutNote: NoteCommitment,
)

@Serializable
internal data class ReverseQuoteRequest(
    val units: Long,
    val user: Address,
    val refundNote: NoteCommitment,
)

/** The swap a maker opened for an accepted quote, with the deadlines it picked: the last of its terms. */
@Serializable
data class SwapAccepted(
    val swapId: SwapId,
    val t0: Long,
    val t1: Long,
)

// What a relayer is sent: the swap's own request, with the terms the contract checks it against, in zecSwap's order.
@Serializable
internal class AuthorizationRequest(
    val swapId: SwapId,
    val terms: SwapTerms,
    val deadline: Long,
    val signature: String,
)

@Serializable
internal class PayoutRequest(
    val swapId: SwapId,
    val terms: SwapTerms,
    val note: SwapNote,
    val fee: Usdc6,
    val signature: String,
)

@Serializable
internal class RevealRequest(
    val swapId: SwapId,
    val terms: SwapTerms,
    val secret: String,
    val payout: PayoutRequest,
)

@Serializable
internal class RescueRequest(
    val swapId: SwapId,
    val terms: SwapTerms,
    val note: SwapNote,
    val fee: Usdc6,
    val nonce: Long,
    val deadline: Long,
    val signature: String,
)

internal fun SwapAuthorization.request(terms: SwapTerms) = AuthorizationRequest(swapId, terms, deadline, signature)

internal fun SwapPayout.request(terms: SwapTerms) = PayoutRequest(swapId, terms, note, fee, signature)

internal fun SwapReveal.request(terms: SwapTerms) = RevealRequest(swapId, terms, secret, payout.request(terms))

internal fun SwapRescue.request(terms: SwapTerms) =
    RescueRequest(swapId, terms, note, fee, nonce, deadline, signature)

internal fun SwapQuote.requireWellFormed() {
    fixedHex(quoteId, SWAP_WORD_BYTES)
    fixedHex(makerProof, SWAP_SHARE_BYTES)
    requireSwapAmount(amount, positive = true)
    require(depositZat in 1..MAX_ZATOSHI && expiresAt > 0) { "the quote's terms are empty" }
}

internal fun RelayerTerms.requireWellFormed() = requireSwapAmount(fee, positive = false)

internal fun PayoutNote.wire() = SwapNote(npk.hex(), encryptedBundle.map { it.hex() }, shieldKey.hex())

internal fun UserAcceptance.wire() = SwapAcceptance(userShare.hex, userProof.hex(), viewingKeys.hex())
