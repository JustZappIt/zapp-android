package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import io.ktor.client.plugins.ResponseException
import java.math.BigDecimal

interface SwapDataSource {
    @Throws(ResponseException::class)
    suspend fun getSupportedTokens(): List<SwapAsset>

    @Throws(ResponseException::class, QuoteLowAmountException::class)
    suspend fun requestQuote(
        swapMode: SwapMode,
        flexInput: Boolean,
        amount: BigDecimal,
        refundAddress: String,
        originAsset: SwapAsset,
        destinationAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal,
        affiliateAddress: String
    ): SwapQuote

    /**
     * Prices a swap without opening one. [requestQuote] reserves a deposit address the provider then
     * tracks as a swap awaiting funds, so using it to fill in a preview leaves an abandoned record
     * behind every time a screen opens or an amount changes. Use this for anything the user has not
     * yet committed to.
     *
     * Deliberately has no default delegating to [requestQuote]: an implementation that forgets to
     * price without reserving is the bug this exists to prevent, so it has to be a compile error.
     */
    @Throws(ResponseException::class, QuoteLowAmountException::class)
    suspend fun requestQuoteEstimate(
        swapMode: SwapMode,
        flexInput: Boolean,
        amount: BigDecimal,
        refundAddress: String,
        originAsset: SwapAsset,
        destinationAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal,
        affiliateAddress: String
    ): SwapQuoteEstimate

    @Throws(ResponseException::class)
    suspend fun submitDepositTransaction(txHash: String, depositAddress: String)

    @Throws(ResponseException::class, TokenNotFoundException::class)
    suspend fun checkSwapStatus(depositAddress: String, supportedTokens: List<SwapAsset>): SwapQuoteStatus
}

/** What a swap would cost right now. Carries no deposit address: nothing was reserved. */
data class SwapQuoteEstimate(
    val amountIn: BigDecimal,
    val estimatedDurationSeconds: Int?,
    val affiliateFeeZatoshi: Zatoshi,
    val slippage: BigDecimal
)

class QuoteLowAmountException(
    val asset: SwapAsset,
    val amount: BigDecimal?,
    val amountFormatted: BigDecimal?
) : Exception()

class TokenNotFoundException(
    tokenId: String
) : Exception("Token $tokenId not found")
