package co.electriccoin.zcash.ui.common.invest.repository

import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
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
import co.electriccoin.zcash.ui.common.provider.BridgeAuthorizationCancelledException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Duration
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

    // One fresh shielded address for every estimate this session: a dry quote carries its refund address to
    // 1Click with the account ID, so it must not be the address the wallet shows on Receive.
    @Volatile
    private var estimateRefundAddress: String? = null

    // Deposit addresses being paid right now, so a second tap can't send twice for one quote.
    private val paying = mutableSetOf<String>()
    private val payingMutex = Mutex()

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
                    val raw = balance.available.toBigDecimalOrNull() ?: return@mapNotNull null
                    val units = raw.movePointLeft(token.decimals).stripTrailingZeros()
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
        val catalog = catalogNoOlderThan(CATALOG_MAX_AGE)
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
                        refundTo = estimateRefundAddress ?: wallet.zcashAddress().also { estimateRefundAddress = it },
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
        // The ZEC amount comes from this price, so it is never more than a minute old here.
        val catalog = catalogNoOlderThan(CATALOG_MAX_AGE)
        val destinationAsset = requireNotNull(catalog.swapAssets[asset.assetId]) { "1Click no longer lists this stock" }
        val zats = zatsFor(usdAmount, catalog)
        val accountId = keys.accountId()
        // A fresh shielded address per buy, so a refund doesn't link this buy to any other payment.
        val refundTo = wallet.zcashAddress()
        val response =
            api.requestQuote(
                quoteRequest(dry = false, zats = zats, asset = asset, refundTo = refundTo, accountId = accountId),
            )
        // Checked before the review sheet ever shows it: the ZEC goes to this deposit address, so the quote
        // must be for exactly what was asked. 1Click echoes these verbatim for Invest (captured 2026-09-25/26).
        val echo = response.quoteRequest
        requireEcho("dry", false, echo.dry)
        requireEcho("swapType", SwapType.EXACT_INPUT, echo.swapType)
        requireEcho("originAsset", ZEC_ASSET_ID, echo.originAsset)
        requireEcho("destinationAsset", asset.assetId, echo.destinationAsset)
        requireEcho("depositType", RefundType.ORIGIN_CHAIN, echo.depositType)
        requireEcho("refundType", RefundType.ORIGIN_CHAIN, echo.refundType)
        requireEcho("recipient", accountId, echo.recipient)
        requireEcho("recipientType", RecipientType.CONFIDENTIAL_INTENTS, echo.recipientType)
        requireEcho("refundTo", refundTo, echo.refundTo)
        // compareTo, not equals: a value like 6,480,000 carries a different scale once trailing zeros go.
        require(response.quote.amountIn.compareTo(BigDecimal(zats)) == 0) {
            "Invest quote amountIn differs from the request"
        }
        // A second guard against a stale price: the ZEC being sent must be worth about what the user typed.
        require(
            (response.quote.amountInUsd - usdAmount).abs() <= usdAmount.multiply(MAX_USD_DRIFT),
        ) { "Invest quote is priced too far from the amount entered" }
        val depositAddress =
            requireNotNull(response.quote.depositAddress?.takeIf { it.isNotBlank() }) {
                "1Click returned an executable quote with no deposit address"
            }
        val quote =
            NearSwapQuote(
                response = response,
                originAsset = catalog.zecAsset,
                destinationAsset = destinationAsset,
                depositAddress = zcashSwapAddress(depositAddress),
                destinationAddress = DynamicSwapAddress(accountId),
                refundAddress = ZcashShieldedSwapAddress(refundTo),
                expectedSlippageToleranceBps = SLIPPAGE_BPS,
            )
        return PreparedBuy(
            asset = asset,
            zecIn = response.quote.amountInFormatted,
            unitsOutExpected = response.quote.amountOutFormatted,
            unitsOutMin = response.quote.minAmountOut.movePointLeft(destinationAsset.decimals),
            usdOut = response.quote.amountOutUsd,
            feesUsd = (response.quote.amountInUsd - response.quote.amountOutUsd).max(BigDecimal.ZERO),
            refundFeeZec = response.quote.refundFee?.movePointLeft(ZEC_DECIMALS),
            etaSeconds = response.quote.timeEstimate,
            expiresAt = minOf(now() + PRICE_HELD, quote.deadline),
            quote = quote,
        )
    }

    override suspend fun executeBuy(prepared: PreparedBuy): String {
        check(now() < prepared.expiresAt) { "The price is no longer held; prepare the buy again" }
        val depositAddress = prepared.quote.depositAddress.address
        payingMutex.withLock {
            check(depositAddress !in paying) { "This buy is already being paid" }
            check(checkpoints.observe().first().none { it.depositAddress == depositAddress }) {
                "This buy was already paid"
            }
            paying += depositAddress
        }
        try {
            // Persisted BEFORE any ZEC moves: a crash after sending must resume polling this deposit address,
            // never prepare and pay for a second buy.
            checkpoints.add(InvestBuyCheckpoint(depositAddress, prepared.asset.assetId, now().toEpochMilliseconds()))
            try {
                wallet.sendZecDeposit(prepared.quote)
            } catch (e: BridgeAuthorizationCancelledException) {
                checkpoints.remove(depositAddress)
                throw e
            } catch (e: TransactionProposalNotCreatedException) {
                checkpoints.remove(depositAddress)
                throw e
            } catch (e: InsufficientFundsException) {
                checkpoints.remove(depositAddress)
                throw e
            }
            // Any other failure may come after a broadcast (a partial or gRPC result): the checkpoint stays, and
            // polling finds out whether 1Click saw the deposit or the quote expired without one.
        } finally {
            payingMutex.withLock { paying -= depositAddress }
        }
        return depositAddress
    }

    override fun observeBuy(depositAddress: String): Flow<BuyProgress> =
        flow {
            var rejectedPolls = 0
            while (true) {
                val progress =
                    try {
                        api.checkStatus(depositAddress).toProgress(depositAddress).also { rejectedPolls = 0 }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: InvestApiException.Api) {
                        // A 4xx answers the same way every time; after a few, say so instead of polling forever.
                        rejectedPolls = if (e.status in HTTP_CLIENT_ERRORS) rejectedPolls + 1 else 0
                        if (rejectedPolls >= MAX_REJECTED_POLLS) {
                            BuyProgress.NeedsAttention(depositAddress, reference = depositAddress)
                        } else {
                            null
                        }
                    } catch (_: InvestApiException) {
                        // 1Click runs the buy whatever our polling does; a failed poll is only a missed update.
                        null
                    }
                if (progress != null) {
                    // Done before the final emit: a collector may stop at the final state and never resume us.
                    if (progress.isFinal) settle(progress)
                    emit(progress)
                    if (progress.isFinal) break
                }
                delay(pollIntervalMillis)
            }
        }

    private suspend fun settle(progress: BuyProgress) {
        // A buy that needs attention stays listed, so the user can find it again and reach support.
        if (progress !is BuyProgress.NeedsAttention) checkpoints.remove(progress.depositAddress)
        if (progress is BuyProgress.Held) {
            try {
                refreshHoldings()
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvestApiException) {
                // The holding shows on the next refresh.
            }
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
            Catalog(tokens = tokens, zecAsset = zecAsset, swapAssets = swapAssets, loadedAt = now())
                .also { catalog = it }
        }

    private suspend fun catalogNoOlderThan(maxAge: Duration): Catalog =
        catalog?.takeIf { now() - it.loadedAt < maxAge } ?: loadCatalog()

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
            SwapStatus.PENDING_DEPOSIT -> {
                // No deposit by the quote's deadline: 1Click will never run this buy, and refunds any ZEC that
                // arrives late. Without this, a buy whose send never happened would poll forever.
                val deadline = quoteResponse.quote.deadline
                if (deadline != null && now() > deadline) {
                    BuyProgress.Expired(depositAddress)
                } else {
                    BuyProgress.SendingZec(depositAddress)
                }
            }

            SwapStatus.KNOWN_DEPOSIT_TX -> {
                BuyProgress.PaymentReceived(depositAddress, incomplete = false)
            }

            SwapStatus.INCOMPLETE_DEPOSIT -> {
                BuyProgress.PaymentReceived(depositAddress, incomplete = true)
            }

            SwapStatus.PROCESSING -> {
                BuyProgress.Buying(depositAddress)
            }

            SwapStatus.SUCCESS -> {
                BuyProgress.Held(depositAddress, swapDetails?.amountOutFormatted)
            }

            SwapStatus.REFUNDED -> {
                BuyProgress.Refunded(depositAddress, swapDetails?.refundedAmountFormatted)
            }

            SwapStatus.FAILED -> {
                BuyProgress.NeedsAttention(depositAddress, reference = depositAddress)
            }

            null -> {
                null
            }
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
        val loadedAt: Instant,
    )

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
        val CATALOG_MAX_AGE = 1.minutes
        val MAX_USD_DRIFT = BigDecimal("0.05")
        const val MAX_REJECTED_POLLS = 3
        val HTTP_CLIENT_ERRORS = 400..499
        val DEPOSIT_DEADLINE = 2.hours
    }
}

/** Swap assets for the curated stocks, for code that resolves a buy's swap record (status, activity). */
interface InvestSwapAssetSource {
    suspend fun investSwapAssets(): List<SwapAsset>
}
