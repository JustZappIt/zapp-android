package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.crash.android.GlobalCrashReporter
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.ZcashShieldedSwapAddress
import co.electriccoin.zcash.ui.common.model.ZcashSwapAddress
import co.electriccoin.zcash.ui.common.model.ZcashTransparentSwapAddress
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.model.near.AppFee
import co.electriccoin.zcash.ui.common.model.near.NearSwapQuote
import co.electriccoin.zcash.ui.common.model.near.NearSwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.RecipientType
import co.electriccoin.zcash.ui.common.model.near.RefundType
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapAmountInconsistencyException
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.common.model.near.computeAffiliateFeeZatoshi
import co.electriccoin.zcash.ui.common.model.near.requireConsistent
import co.electriccoin.zcash.ui.common.provider.NearApiProvider
import co.electriccoin.zcash.ui.common.provider.ResponseWithNearErrorException
import co.electriccoin.zcash.ui.common.provider.SwapAssetProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.util.loggableNot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@Suppress("TooManyFunctions")
class NearSwapDataSourceImpl(
    private val nearApiProvider: NearApiProvider,
    private val swapAssetProvider: SwapAssetProvider,
    private val synchronizerProvider: SynchronizerProvider,
) : SwapDataSource {
    private val log = loggableNot("NearSwapDataSourceImpl")

    override suspend fun getSupportedTokens(): List<SwapAsset> =
        withContext(Dispatchers.Default) {
            nearApiProvider
                .getSupportedTokens()
                .distinctBy { Triple(it.symbol, it.blockchain, it.decimals) }
                .map {
                    swapAssetProvider.get(
                        tokenTicker = it.symbol,
                        chainTicker = it.blockchain,
                        usdPrice = it.price,
                        assetId = it.assetId,
                        decimals = it.decimals
                    )
                }
        }

    override suspend fun requestQuote(
        swapMode: SwapMode,
        flexInput: Boolean,
        amount: BigDecimal,
        refundAddress: String,
        originAsset: SwapAsset,
        destinationAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal,
        affiliateAddress: String
    ): SwapQuote {
        val slippageToleranceBps = slippageBps(slippage)
        val request =
            buildQuoteRequest(
                dry = false,
                swapMode = swapMode,
                flexInput = flexInput,
                amount = amount,
                refundAddress = refundAddress,
                originAsset = originAsset,
                destinationAddress = destinationAddress,
                destinationAsset = destinationAsset,
                slippageToleranceBps = slippageToleranceBps,
                affiliateAddress = affiliateAddress
            )

        return try {
            val response = nearApiProvider.requestQuote(request)
            require(response.quoteRequest.swapType == request.swapType) {
                "Swap quote type mismatch: requested ${request.swapType} " +
                    "but server returned ${response.quoteRequest.swapType}"
            }
            NearSwapQuote(
                response = response,
                originAsset = originAsset,
                destinationAsset = destinationAsset,
                depositAddress = getDepositAddress(response, originAsset),
                destinationAddress = getDestinationAddress(response, originAsset),
                refundAddress = getRefundAddress(response, originAsset),
                expectedSlippageToleranceBps = slippageToleranceBps,
            )
        } catch (e: SwapAmountInconsistencyException) {
            // MOB-1371 monitoring signal: the exact-equality amount-consistency check rejected this quote.
            // Report a sanitized non-fatal (field + decimals only, never the amounts — see the release
            // log-redaction hardening) so that a future 1Click change to rounded display values surfaces as
            // an observable "quotes blocked" signal instead of silent breakage. Keep failing closed: rethrow
            // so the quote is still rejected.
            GlobalCrashReporter.reportCaughtException(
                SwapAmountConsistencyRejectedSignal(field = e.field, decimals = e.decimals)
            )
            throw e
        } catch (e: ResponseWithNearErrorException) {
            throw mapQuoteError(e, swapMode, originAsset, destinationAsset)
        }
    }

    /**
     * `dry = true`, so 1Click prices the route and hands back no deposit address. Nothing is reserved
     * and nothing appears in the provider's transaction list, which is what makes this safe to call
     * while the user is still typing.
     */
    override suspend fun requestQuoteEstimate(
        swapMode: SwapMode,
        flexInput: Boolean,
        amount: BigDecimal,
        refundAddress: String,
        originAsset: SwapAsset,
        destinationAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal,
        affiliateAddress: String
    ): SwapQuoteEstimate {
        val slippageToleranceBps = slippageBps(slippage)
        val request =
            buildQuoteRequest(
                dry = true,
                swapMode = swapMode,
                flexInput = flexInput,
                amount = amount,
                refundAddress = refundAddress,
                originAsset = originAsset,
                destinationAddress = destinationAddress,
                destinationAsset = destinationAsset,
                slippageToleranceBps = slippageToleranceBps,
                affiliateAddress = affiliateAddress
            )

        val response =
            try {
                nearApiProvider.requestQuote(request)
            } catch (e: ResponseWithNearErrorException) {
                throw mapQuoteError(e, swapMode, originAsset, destinationAsset)
            }

        require(response.quoteRequest.swapType == request.swapType) {
            "Swap quote type mismatch: requested ${request.swapType} " +
                "but server returned ${response.quoteRequest.swapType}"
        }
        // A preview quotes the same route the executable quote will, so it is held to the same
        // amount-consistency check rather than displaying a figure the real quote would reject.
        requireConsistent(
            name = "amountIn",
            raw = response.quote.amountIn,
            formatted = response.quote.amountInFormatted,
            decimals = originAsset.decimals
        )
        requireConsistent(
            name = "amountOut",
            raw = response.quote.amountOut,
            formatted = response.quote.amountOutFormatted,
            decimals = destinationAsset.decimals
        )

        return SwapQuoteEstimate(
            amountIn = response.quote.amountIn,
            estimatedDurationSeconds = response.quote.timeEstimate,
            affiliateFeeZatoshi =
                computeAffiliateFeeZatoshi(
                    originAsset = originAsset,
                    amountInFormatted = response.quote.amountInFormatted,
                    amountInUsd = response.quote.amountInUsd,
                    amountOutUsd = response.quote.amountOutUsd
                ),
            // From the client's own request, not the server's echo: a preview that quietly widened
            // its slippage would be misleading in exactly the direction that matters.
            slippage = BigDecimal(slippageToleranceBps).divide(BASIS_POINTS_PER_PERCENT, MathContext.DECIMAL128)
        )
    }

    private fun slippageBps(slippage: BigDecimal): Int =
        slippage.multiply(BASIS_POINTS_PER_PERCENT, MathContext.DECIMAL128).toInt()

    private fun buildQuoteRequest(
        dry: Boolean,
        swapMode: SwapMode,
        flexInput: Boolean,
        amount: BigDecimal,
        refundAddress: String,
        originAsset: SwapAsset,
        destinationAddress: String,
        destinationAsset: SwapAsset,
        slippageToleranceBps: Int,
        affiliateAddress: String
    ): QuoteRequest {
        val decimals =
            when (swapMode) {
                SwapMode.EXACT_INPUT, SwapMode.FLEX_INPUT -> originAsset.decimals
                SwapMode.EXACT_OUTPUT -> destinationAsset.decimals
            }

        val shifted = amount.movePointRight(decimals)
        val integer = shifted.toBigInteger().toBigDecimal()
        val normalizedAmount = shifted.round(MathContext(integer.precision(), RoundingMode.DOWN))

        return QuoteRequest(
            dry = dry,
            swapType =
                when {
                    flexInput -> SwapType.FLEX_INPUT
                    swapMode == SwapMode.EXACT_INPUT -> SwapType.EXACT_INPUT
                    else -> SwapType.EXACT_OUTPUT
                },
            slippageTolerance = slippageToleranceBps,
            originAsset = originAsset.assetId,
            depositType = RefundType.ORIGIN_CHAIN,
            destinationAsset = destinationAsset.assetId,
            amount = normalizedAmount,
            refundTo = refundAddress,
            refundType = RefundType.ORIGIN_CHAIN,
            recipient = destinationAddress,
            recipientType = RecipientType.DESTINATION_CHAIN,
            deadline = Clock.System.now() + QUOTE_DEADLINE,
            quoteWaitingTimeMs = QUOTE_WAITING_TIME,
            appFees =
                if (AFFILIATE_FEE_BPS > 0) {
                    listOf(
                        AppFee(
                            recipient = affiliateAddress,
                            fee = AFFILIATE_FEE_BPS
                        )
                    )
                } else {
                    emptyList()
                },
            referral = "zapp"
        )
    }

    private fun mapQuoteError(
        e: ResponseWithNearErrorException,
        swapMode: SwapMode,
        originAsset: SwapAsset,
        destinationAsset: SwapAsset
    ): Exception {
        // The floated side is the one the provider is complaining about.
        val lowAsset =
            when (swapMode) {
                SwapMode.EXACT_INPUT, SwapMode.FLEX_INPUT -> originAsset
                SwapMode.EXACT_OUTPUT -> destinationAsset
            }
        val tooLow = e.error.message.contains("Amount is too low for bridge, try at least", true)
        val lowAmount =
            if (tooLow) {
                e.error.message
                    .split(" ")
                    .lastOrNull()
                    ?.toBigDecimalOrNull()
            } else {
                null
            }
        return when {
            // An unparseable minimum falls through to the original error rather than inventing one.
            tooLow && lowAmount != null -> {
                QuoteLowAmountException(
                    asset = lowAsset,
                    amount = lowAmount,
                    amountFormatted = lowAmount.movePointLeft(lowAsset.decimals)
                )
            }

            e.error.message.contains("No quotes found", true) -> {
                QuoteLowAmountException(
                    asset = originAsset,
                    amount = null,
                    amountFormatted = null
                )
            }

            else -> {
                e
            }
        }
    }

    override suspend fun submitDepositTransaction(txHash: String, depositAddress: String) {
        nearApiProvider.submitDepositTransaction(
            SubmitDepositTransactionRequest(
                txHash = txHash,
                depositAddress = depositAddress
            )
        )
    }

    override suspend fun checkSwapStatus(depositAddress: String, supportedTokens: List<SwapAsset>): SwapQuoteStatus {
        val response = this.nearApiProvider.checkSwapStatus(depositAddress)
        val originAsset =
            findAssetByEchoedId(supportedTokens, response.quoteResponse.quoteRequest.originAsset)
                ?: throw TokenNotFoundException(response.quoteResponse.quoteRequest.originAsset)
        val destinationAsset =
            findAssetByEchoedId(supportedTokens, response.quoteResponse.quoteRequest.destinationAsset)
                ?: throw TokenNotFoundException(response.quoteResponse.quoteRequest.destinationAsset)
        log("checkSwapStatus")
        return NearSwapQuoteStatus(
            response = response,
            origin = originAsset,
            destination = destinationAsset,
            depositAddress = getDepositAddress(response.quoteResponse, originAsset),
            destinationAddress = getDestinationAddress(response.quoteResponse, originAsset),
            refundAddress = getRefundAddress(response.quoteResponse, originAsset),
        )
    }

    // The 1Click API normalises asset IDs for routing (e.g. "nep141:btc.omft.near" →
    // "1cs_v1:btc:native:coin"). Try an exact match first; fall back to extracting the ticker
    // from the normalised "1cs_v1:<ticker>:..." format and matching by tokenTicker.
    private fun findAssetByEchoedId(supportedTokens: List<SwapAsset>, echoedId: String): SwapAsset? =
        supportedTokens.find { it.assetId == echoedId }
            ?: echoedId.split(":").getOrNull(1)?.let { ticker ->
                supportedTokens.find { it.tokenTicker.equals(ticker, ignoreCase = true) }
            }

    private suspend fun getDepositAddress(response: QuoteResponseDto, originAsset: SwapAsset): SwapAddress {
        // Only a dry quote legitimately omits this, and a dry quote never reaches here.
        val address =
            requireNotNull(response.quote.depositAddress?.takeIf { it.isNotBlank() }) {
                "1Click returned an executable quote with no deposit address"
            }
        return if (originAsset is ZecSwapAsset) getZcashSwapAddress(address) else DynamicSwapAddress(address)
    }

    private suspend fun getDestinationAddress(response: QuoteResponseDto, originAsset: SwapAsset): SwapAddress {
        val address = response.quoteRequest.recipient
        return if (originAsset is ZecSwapAsset) DynamicSwapAddress(address) else getZcashSwapAddress(address)
    }

    private suspend fun getRefundAddress(response: QuoteResponseDto, originAsset: SwapAsset): SwapAddress {
        val address = response.quoteRequest.refundTo
        return if (originAsset is ZecSwapAsset) getZcashSwapAddress(address) else DynamicSwapAddress(address)
    }

    private suspend fun getZcashSwapAddress(address: String): ZcashSwapAddress =
        when (synchronizerProvider.getSynchronizer().validateAddress(address)) {
            AddressType.Unified,
            AddressType.Shielded -> ZcashShieldedSwapAddress(address)

            AddressType.Tex,
            AddressType.Transparent,
            is AddressType.Invalid -> ZcashTransparentSwapAddress(address)
        }
}

// App fee disabled: swaps carry no Zapp fee. Set back to 67 to re-enable it — the quote request,
// the fee display and the offramp bridge estimate all derive from this one constant.
const val AFFILIATE_FEE_BPS = 0
const val AFFILIATE_ADDRESS = "042269ffc94d52b822b4bd053f9122c5a890a5483822421ac35a5236f63e390d"
private const val QUOTE_WAITING_TIME = 3000
private val QUOTE_DEADLINE = 2.hours
private val BASIS_POINTS_PER_PERCENT = BigDecimal("100")

/**
 * Sanitized non-fatal reported to crash monitoring when the swap amount-consistency check rejects a quote
 * (MOB-1371). Carries only the field name and decimal precision — never the amounts — so it does not leak
 * transaction values to crash reporting.
 */
private class SwapAmountConsistencyRejectedSignal(
    field: String,
    decimals: Int
) : Exception("Swap amount-consistency check rejected a quote (field=$field, decimals=$decimals)")
