package co.electriccoin.zcash.ui.common.invest.repository

import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.invest.model.BuyEstimate
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.Holding
import co.electriccoin.zcash.ui.common.invest.model.Holdings
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.model.MarketAsset
import co.electriccoin.zcash.ui.common.invest.model.PreparedBuy
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiException
import co.electriccoin.zcash.ui.common.invest.provider.InvestApiProvider
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpoint
import co.electriccoin.zcash.ui.common.invest.provider.InvestBuyCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountKeyProvider
import co.electriccoin.zcash.ui.common.invest.provider.PrivateAccountSession
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.ZcashShieldedSwapAddress
import co.electriccoin.zcash.ui.common.model.ZcashSwapAddress
import co.electriccoin.zcash.ui.common.model.ZcashTransparentSwapAddress
import co.electriccoin.zcash.ui.common.model.near.Confidentiality
import co.electriccoin.zcash.ui.common.model.near.NearSwapQuote
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.RecipientType
import co.electriccoin.zcash.ui.common.model.near.RefundType
import co.electriccoin.zcash.ui.common.model.near.SwapStatus
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.common.provider.OfframpBridgeWallet
import co.electriccoin.zcash.ui.common.provider.SwapAssetProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.ZEC_BRIDGE_FEE_RESERVE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Invest's repository. A buy is a 1Click swap from ZEC into one of the curated Ondo stocks, delivered to the
 * user's private account (`recipientType CONFIDENTIAL_INTENTS`). The ZEC leaves through the same biometric
 * send the Base bridge uses ([OfframpBridgeWallet.sendZecDeposit]), which also records it as a swap in the
 * wallet's metadata; the activity list recognises an Invest buy by its destination being a curated stock.
 */
@Suppress("TooManyFunctions")
internal class InvestRepositoryImpl(
    private val api: InvestApiProvider,
    private val session: PrivateAccountSession,
    private val keys: PrivateAccountKeyProvider,
    private val wallet: OfframpBridgeWallet,
    private val accountDataSource: AccountDataSource,
    private val swapAssetProvider: SwapAssetProvider,
    private val synchronizerProvider: SynchronizerProvider,
    private val checkpoints: InvestBuyCheckpointStorageProvider,
    private val now: () -> Instant = { Clock.System.now() },
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MS,
) : InvestRepository,
    InvestSwapAssetSource {
    private val _market = MutableStateFlow<InvestMarket?>(null)
    private val _holdings = MutableStateFlow<Holdings?>(null)
    private val catalogMutex = Mutex()

    @Volatile
    private var catalog: Catalog? = null

    override val market: StateFlow<InvestMarket?> = _market.asStateFlow()

    override val holdings: StateFlow<Holdings?> = _holdings.asStateFlow()

    override val pendingBuys: Flow<List<String>> = checkpoints.observe().map { list -> list.map { it.depositAddress } }

    override suspend fun refreshMarket() {
        loadCatalog()
    }

    override suspend fun refreshHoldings() {
        try {
            val catalog = catalog ?: loadCatalog()
            val balances = session.balances().balances
            val items =
                balances.mapNotNull { balance ->
                    val asset = InvestAssets.find(balance.tokenId) ?: return@mapNotNull null
                    val token = catalog.tokens[asset.assetId] ?: return@mapNotNull null
                    val units = BigDecimal(balance.available).movePointLeft(token.decimals).stripTrailingZeros()
                    if (units.signum() <= 0) return@mapNotNull null
                    Holding(asset = asset, units = units, usdValue = token.price?.let { units.multiply(it) })
                }
            val priced = items.mapNotNull { it.usdValue }
            _holdings.value =
                Holdings(
                    items = items,
                    totalUsd = if (priced.isEmpty()) null else priced.fold(BigDecimal.ZERO, BigDecimal::add),
                    updatedAt = now(),
                    isStale = false,
                )
        } catch (e: InvestApiException) {
            _holdings.value = _holdings.value?.copy(isStale = true)
            throw e
        }
    }

    // Each early return is one of the estimate's outcomes, checked in the order the screen explains them.
    @Suppress("ReturnCount")
    override suspend fun estimateBuy(
        asset: InvestAsset,
        usdAmount: BigDecimal,
    ): BuyEstimate {
        if (usdAmount < InvestRepository.MINIMUM_USD) return BuyEstimate.BelowMinimum(InvestRepository.MINIMUM_USD)
        val catalog = catalog ?: loadCatalog()
        val zats = zatsFor(usdAmount, catalog)
        val spendable = wallet.spendableZec()
        if (zats + ZEC_BRIDGE_FEE_RESERVE.value > spendable.value) {
            return BuyEstimate.InsufficientZec(spendable.value.toZec())
        }
        val response =
            try {
                api.requestQuote(
                    quoteRequest(
                        dry = true,
                        zats = zats,
                        asset = asset,
                        refundTo =
                            accountDataSource
                                .getSelectedAccount()
                                .unified.address.address,
                        accountId = keys.accountId(),
                    ),
                )
            } catch (_: InvestApiException.NoPrice) {
                return BuyEstimate.NoPrice
            }
        return BuyEstimate.Priced(
            zecIn = response.quote.amountInFormatted,
            unitsOut = response.quote.amountOutFormatted,
            usdOut = response.quote.amountOutUsd,
            feesUsd = (response.quote.amountInUsd - response.quote.amountOutUsd).max(BigDecimal.ZERO),
            etaSeconds = response.quote.timeEstimate,
            refundFeeZec = response.quote.refundFee?.movePointLeft(ZEC_DECIMALS),
        )
    }

    override suspend fun prepareBuy(
        asset: InvestAsset,
        usdAmount: BigDecimal,
    ): PreparedBuy {
        require(usdAmount >= InvestRepository.MINIMUM_USD) { "Below the Invest minimum" }
        val catalog = catalog ?: loadCatalog()
        val zats = zatsFor(usdAmount, catalog)
        val accountId = keys.accountId()
        // A fresh shielded address per buy, so a refund doesn't link this buy to any other payment.
        val refundTo = wallet.zcashAddress()
        val response =
            api.requestQuote(
                quoteRequest(dry = false, zats = zats, asset = asset, refundTo = refundTo, accountId = accountId),
            )
        // Checked before the review sheet ever shows it: the ZEC goes to this deposit address, so the quote
        // must be for exactly what was asked. Asset IDs aren't compared: 1Click may normalise them.
        val echo = response.quoteRequest
        requireEcho("recipient", accountId, echo.recipient)
        requireEcho("recipientType", RecipientType.CONFIDENTIAL_INTENTS, echo.recipientType)
        requireEcho("refundTo", refundTo, echo.refundTo)
        requireEcho("amountIn", BigDecimal(zats), response.quote.amountIn.stripTrailingZeros())
        val depositAddress =
            requireNotNull(response.quote.depositAddress?.takeIf { it.isNotBlank() }) {
                "1Click returned an executable quote with no deposit address"
            }
        val quote =
            NearSwapQuote(
                response = response,
                originAsset = catalog.zecAsset,
                destinationAsset = catalog.swapAssets.getValue(asset.assetId),
                depositAddress = zcashSwapAddress(depositAddress),
                destinationAddress = DynamicSwapAddress(accountId),
                refundAddress = ZcashShieldedSwapAddress(refundTo),
                expectedSlippageToleranceBps = SLIPPAGE_BPS,
            )
        return PreparedBuy(
            asset = asset,
            zecIn = response.quote.amountInFormatted,
            unitsOutExpected = response.quote.amountOutFormatted,
            unitsOutMin = response.quote.minAmountOut.movePointLeft(catalog.decimalsOf(asset)),
            usdOut = response.quote.amountOutUsd,
            feesUsd = (response.quote.amountInUsd - response.quote.amountOutUsd).max(BigDecimal.ZERO),
            refundFeeZec = response.quote.refundFee?.movePointLeft(ZEC_DECIMALS),
            etaSeconds = response.quote.timeEstimate,
            expiresAt = minOf(now() + PRICE_HELD, response.quote.deadline ?: (now() + PRICE_HELD)),
            quote = quote,
        )
    }

    override suspend fun executeBuy(prepared: PreparedBuy): String {
        check(now() < prepared.expiresAt) { "The price is no longer held; prepare the buy again" }
        val depositAddress = prepared.quote.depositAddress.address
        // Persisted BEFORE any ZEC moves: a crash after sending must resume polling this deposit address,
        // never prepare and pay for a second buy.
        checkpoints.add(InvestBuyCheckpoint(depositAddress, prepared.asset.assetId, now().toEpochMilliseconds()))
        try {
            wallet.sendZecDeposit(prepared.quote)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // Nothing was submitted (a cancelled prompt or a failed proposal); there is nothing to resume.
            checkpoints.remove(depositAddress)
            throw e
        }
        return depositAddress
    }

    override fun observeBuy(depositAddress: String): Flow<BuyProgress> =
        flow {
            while (true) {
                val progress =
                    try {
                        api.checkStatus(depositAddress).toProgress(depositAddress)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: InvestApiException) {
                        // 1Click runs the buy whatever our polling does; a failed poll is only a missed update.
                        null
                    }
                if (progress != null) {
                    emit(progress)
                    if (progress.isFinal) {
                        checkpoints.remove(depositAddress)
                        if (progress is BuyProgress.Held) runCatching { refreshHoldings() }
                        break
                    }
                }
                delay(pollIntervalMillis)
            }
        }

    /** The curated stocks as swap assets, so a buy's swap record resolves in status polling and activity. */
    override suspend fun investSwapAssets(): List<SwapAsset> = (catalog ?: loadCatalog()).swapAssets.values.toList()

    private suspend fun loadCatalog(): Catalog =
        catalogMutex.withLock {
            val tokens = api.getOndoTokens().associateBy { it.assetId }
            val zecToken = requireNotNull(tokens[ZEC_ASSET_ID]) { "1Click listed no ZEC" }
            val zecAsset = zecToken.toSwapAsset()
            val swapAssets =
                InvestAssets.curated
                    .mapNotNull { asset -> tokens[asset.assetId]?.let { asset.assetId to it.toSwapAsset() } }
                    .toMap()
            _market.value =
                InvestMarket(
                    assets = InvestAssets.curated.map { MarketAsset(it, tokens[it.assetId]?.price) },
                    updatedAt = now(),
                )
            Catalog(tokens = tokens, zecAsset = zecAsset, swapAssets = swapAssets).also { catalog = it }
        }

    private fun zatsFor(
        usdAmount: BigDecimal,
        catalog: Catalog,
    ): Long {
        val zecPrice =
            requireNotNull(catalog.tokens[ZEC_ASSET_ID]?.price?.takeIf { it.signum() > 0 }) { "No ZEC price" }
        return usdAmount
            .divide(zecPrice, ZEC_DECIMALS, RoundingMode.DOWN)
            .movePointRight(ZEC_DECIMALS)
            .longValueExact()
    }

    private fun quoteRequest(
        dry: Boolean,
        zats: Long,
        asset: InvestAsset,
        refundTo: String,
        accountId: String,
    ) = QuoteRequest(
        dry = dry,
        swapType = SwapType.EXACT_INPUT,
        slippageTolerance = SLIPPAGE_BPS,
        originAsset = ZEC_ASSET_ID,
        depositType = RefundType.ORIGIN_CHAIN,
        destinationAsset = asset.assetId,
        amount = BigDecimal(zats),
        refundTo = refundTo,
        refundType = RefundType.ORIGIN_CHAIN,
        recipient = accountId,
        recipientType = RecipientType.CONFIDENTIAL_INTENTS,
        deadline = now() + DEPOSIT_DEADLINE,
        quoteWaitingTimeMs = QUOTE_WAITING_TIME_MS,
        appFees = emptyList(),
        referral = REFERRAL,
        confidentiality = Confidentiality.BASIC,
    )

    private suspend fun zcashSwapAddress(address: String): ZcashSwapAddress =
        when (synchronizerProvider.getSynchronizer().validateAddress(address)) {
            AddressType.Unified, AddressType.Shielded -> ZcashShieldedSwapAddress(address)
            AddressType.Tex, AddressType.Transparent, is AddressType.Invalid -> ZcashTransparentSwapAddress(address)
        }

    private fun NearTokenDto.toSwapAsset(): SwapAsset =
        swapAssetProvider.get(
            tokenTicker = symbol,
            chainTicker = blockchain,
            usdPrice = price,
            assetId = assetId,
            decimals = decimals,
        )

    private fun SwapStatusResponseDto.toProgress(depositAddress: String): BuyProgress? =
        when (status) {
            SwapStatus.PENDING_DEPOSIT -> BuyProgress.SendingZec(depositAddress)
            SwapStatus.KNOWN_DEPOSIT_TX -> BuyProgress.PaymentReceived(depositAddress, incomplete = false)
            SwapStatus.INCOMPLETE_DEPOSIT -> BuyProgress.PaymentReceived(depositAddress, incomplete = true)
            SwapStatus.PROCESSING -> BuyProgress.Buying(depositAddress)
            SwapStatus.SUCCESS -> BuyProgress.Held(depositAddress, swapDetails?.amountOutFormatted)
            SwapStatus.REFUNDED -> BuyProgress.Refunded(depositAddress, swapDetails?.refundedAmountFormatted)
            SwapStatus.FAILED -> BuyProgress.NeedsAttention(depositAddress, reference = depositAddress)
            null -> null
        }

    private fun <T> requireEcho(
        name: String,
        expected: T,
        actual: T,
    ) {
        require(expected == actual) { "Invest quote $name differs from the request" }
    }

    private data class Catalog(
        val tokens: Map<String, NearTokenDto>,
        val zecAsset: SwapAsset,
        val swapAssets: Map<String, SwapAsset>,
    ) {
        fun decimalsOf(asset: InvestAsset): Int = tokens.getValue(asset.assetId).decimals
    }

    private fun Long.toZec(): BigDecimal = BigDecimal(this).movePointLeft(ZEC_DECIMALS)

    private companion object {
        const val ZEC_ASSET_ID = "nep141:zec.omft.near"
        const val ZEC_DECIMALS = 8
        const val SLIPPAGE_BPS = 100
        const val QUOTE_WAITING_TIME_MS = 3_000
        const val REFERRAL = "zapp"
        const val DEFAULT_POLL_INTERVAL_MS = 5_000L

        // How long the review sheet may show one price before asking for a fresh one. Shorter than the
        // deposit deadline on purpose: a stale price is the thing the countdown guards against.
        val PRICE_HELD = 10.minutes
        val DEPOSIT_DEADLINE = 2.hours
    }
}

/** Swap assets for the curated stocks, for code that resolves a buy's swap record (status, activity). */
interface InvestSwapAssetSource {
    suspend fun investSwapAssets(): List<SwapAsset>
}
