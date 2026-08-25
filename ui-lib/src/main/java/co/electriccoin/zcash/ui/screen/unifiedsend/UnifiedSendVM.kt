package co.electriccoin.zcash.ui.screen.unifiedsend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.type.AddressType
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSlippageUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.IsABContactHintVisibleUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectABSwapRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapQuoteIfAvailableUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveABContactPickedUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveClearSendUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendData
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.PreselectSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.common.wallet.zecFiatRate
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.combine
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stripFractionsDynamically
import co.electriccoin.zcash.ui.screen.swap.CurrencyType
import co.electriccoin.zcash.ui.screen.swap.SwapCancelState
import co.electriccoin.zcash.ui.screen.swap.info.CrossPayInfoArgs
import co.electriccoin.zcash.ui.screen.swap.picker.SwapAssetPickerArgs
import co.electriccoin.zcash.ui.screen.swap.slippage.SwapSlippageArgs
import co.electriccoin.zcash.ui.screen.topup.TopUpArgs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext

@Suppress("TooManyFunctions")
internal class UnifiedSendVM(
    private val args: UnifiedSendArgs,
    getSelectedSwapAsset: GetSelectedSwapAssetUseCase,
    getSwapAssetsUseCase: GetSwapAssetsUseCase,
    getSlippage: GetSlippageUseCase,
    getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    preselectSwapAsset: PreselectSwapAssetUseCase,
    private val mapper: UnifiedSendVMMapper,
    private val swapRepository: SwapRepository,
    private val cancelSwap: CancelSwapUseCase,
    private val requestSwapQuote: RequestSwapQuoteUseCase,
    private val navigateToSwapQuoteIfAvailable: NavigateToSwapQuoteIfAvailableUseCase,
    private val validateAddress: ValidateAddressUseCase,
    private val createProposal: CreateProposalUseCase,
    private val observeABContactPicked: ObserveABContactPickedUseCase,
    private val prefillSend: PrefillSendUseCase,
    private val observeClearSend: ObserveClearSendUseCase,
    private val navigateToSelectRecipient: NavigateToSelectRecipientUseCase,
    private val navigateToSelectSwapRecipient: NavigateToSelectABSwapRecipientUseCase,
    private val navigateToScanAddress: NavigateToScanGenericAddressUseCase,
    private val isABContactHintVisibleUseCase: IsABContactHintVisibleUseCase,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    // ── Internal mutable state ────────────────────────────────────────────────

    private val zecAmountInner = MutableStateFlow(NumberTextFieldInnerState())
    private val fiatAmountInner = MutableStateFlow(NumberTextFieldInnerState())
    private val fiatWasLastEdited = MutableStateFlow(false)

    /** Swap: the destination-denominated amount, and the same amount priced in USD. */
    private val tokenAmountInner = MutableStateFlow(NumberTextFieldInnerState())
    private val tokenFiatAmountInner = MutableStateFlow(NumberTextFieldInnerState())

    /**
     * Which side of the swap the user last typed into. Everything downstream — the quote screen,
     * the slippage copy, review, progress and history — already branches on the quote's own mode,
     * so this is the only place the app has to make the choice. It is the single source of truth:
     * an empty destination field does not imply exact-input, it implies no amount yet.
     */
    private val swapMode = MutableStateFlow(SwapMode.EXACT_INPUT)

    /** ZEC-direct: zcash address (string) + validated type */
    private val zcashAddress = MutableStateFlow(args.recipientAddress ?: "")
    private val zcashAddressType = MutableStateFlow<AddressType?>(null)

    /** Swap: raw address string */
    private val swapAddress = MutableStateFlow("")

    /** Swap: address book contact (takes precedence over swapAddress) */
    private val swapContact = MutableStateFlow<EnhancedABContact?>(null)

    private val memoText = MutableStateFlow("")
    private val isRequestingQuote = MutableStateFlow(false)
    private val isCancelStateVisible = MutableStateFlow(false)

    private val manualAmountSwap = MutableStateFlow<Boolean?>(null)

    /** Which denomination the destination field is showing. Purely presentational: both amounts are
     * kept in step as the user types, so flipping this only changes which one is on screen. */
    private val destinationCurrency = MutableStateFlow(CurrencyType.TOKEN)

    // ── Derived flows ─────────────────────────────────────────────────────────

    private val selectedAsset = getSelectedSwapAsset.observe()

    /** Combined zcash recipient — reduces two flows to one for the main combine */
    private val zcashRecipient = combine(zcashAddress, zcashAddressType) { addr, type -> addr to type }

    private val swapAssetsWithRate =
        combine(getSwapAssetsUseCase.observe(), exchangeRateRepository.state) { assets, rate ->
            assets to zecFiatRate(rate, zecUsdPrice = null)
        }

    /** The four amount fields plus the mode they imply, reduced to one flow for the main combine. */
    private val amountInputs =
        combine(
            zecAmountInner,
            fiatAmountInner,
            tokenAmountInner,
            tokenFiatAmountInner,
            swapMode
        ) { zec, fiat, token, tokenFiat, mode ->
            AmountInputs(zec = zec, fiat = fiat, token = token, tokenFiat = tokenFiat, mode = mode)
        }

    /** Which denomination each of the two amount rows is showing. */
    private val displayPrefs =
        combine(manualAmountSwap, destinationCurrency) { manualSwap, currency ->
            manualSwap to currency
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val isABHintVisible =
        combine(swapAddress, zcashAddress, swapContact) { swap, zcash, contact ->
            Triple(swap, zcash, contact)
        }.flatMapLatest { (swap, zcash, contact) ->
            val text = swap.ifBlank { zcash }
            isABContactHintVisibleUseCase.observe(selectedContact = contact, text = text)
        }

    val cancelState =
        isCancelStateVisible
            .map { isVisible ->
                if (isVisible) {
                    SwapCancelState(
                        icon = imageRes(R.drawable.ic_swap_quote_cancel),
                        title = stringRes(R.string.swap_cancel_title),
                        subtitle = stringRes(R.string.swap_cancel_subtitle),
                        negativeButton =
                            ButtonState(
                                text = stringRes(R.string.swap_cancel_negative),
                                onClick = ::onCancelSwapClick
                            ),
                        positiveButton =
                            ButtonState(
                                text = stringRes(R.string.swap_cancel_positive),
                                onClick = ::onDismissCancelClick
                            ),
                        onBack = ::onBack
                    )
                } else {
                    null
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private val internalState =
        combine(
            selectedAsset,
            amountInputs,
            zcashRecipient,
            swapAddress,
            swapContact,
            memoText,
            swapAssetsWithRate,
            getSlippage.observe(),
            getSelectedWalletAccount.observe(),
            isABHintVisible,
            isRequestingQuote,
            displayPrefs,
        ) {
            asset,
            amounts,
            (zcashAddr, zcashType),
            swapAddr,
            contact,
            memo,
            (swapAssets, fiatRate),
            slippage,
            account,
            abHintVisible,
            requesting,
            (manualSwap, destinationCurrency),
            ->
            UnifiedSendInternalState(
                selectedAsset = asset,
                amounts = amounts,
                zcashAddress = zcashAddr,
                zcashAddressType = zcashType,
                swapAddress = swapAddr,
                contact = contact,
                memo = memo,
                swapAssets = swapAssets,
                slippage = slippage,
                account = account,
                fiatRate = fiatRate,
                isABHintVisible = abHintVisible,
                isRequestingQuote = requesting,
                manualAmountSwap = manualSwap,
                destinationCurrency = destinationCurrency,
            )
        }

    val state =
        internalState
            .map { internal ->
                mapper.createState(
                    state = internal,
                    onBack = ::onBack,
                    onAssetPickerClick = ::onAssetPickerClick,
                    onAddressChange = ::onAddressChange,
                    onAddressBookClick = ::onAddressBookClick,
                    onQrScannerClick = ::onQrScannerClick,
                    onDeleteSwapContactClick = ::onDeleteSwapContactClick,
                    onCrossPayInfoClick = ::onCrossPayInfoClick,
                    onZecAmountChange = ::onZecAmountChange,
                    onFiatAmountChange = ::onFiatAmountChange,
                    onTokenAmountChange = ::onTokenAmountChange,
                    onTokenFiatAmountChange = ::onTokenFiatAmountChange,
                    onPayEstimateClick = ::onPayEstimateClick,
                    onDestinationCurrencySwap = ::onDestinationCurrencySwap,
                    onAmountSwap = ::onAmountSwap,
                    onMemoChange = ::onMemoChange,
                    onSlippageClick = ::onSlippageClick,
                    onPrimaryButtonClick = ::onPrimaryButtonClick,
                    onTryAgainClick = ::onTryAgainClick,
                    onTopUpClick = ::onTopUpClick,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    init {
        // Validate the address supplied via nav args (e.g. from Chat or QR scan)
        if (!args.recipientAddress.isNullOrBlank()) {
            viewModelScope.launch {
                zcashAddressType.update { validateAddress(args.recipientAddress) }
            }
        }

        selectedAsset
            .filterNotNull()
            .distinctUntilChangedBy { it.assetId }
            .drop(1)
            .onEach {
                zcashAddress.update { "" }
                zcashAddressType.update { null }
                swapAddress.update { "" }
                swapContact.update { null }
                clearTokenAmount()
            }.launchIn(viewModelScope)

        exchangeRateRepository.state
            .map { zecFiatRate(it, zecUsdPrice = null) }
            .distinctUntilChanged()
            .onEach { rate ->
                rate ?: return@onEach
                if (fiatWasLastEdited.value) {
                    updateZecAmount(fiatAmountInner.value.amount, rate)
                } else {
                    updateFiatAmount(zecAmountInner.value.amount, rate)
                }
            }.launchIn(viewModelScope)

        // Listen for ZEC-direct AB picks (shared bus)
        viewModelScope.launch {
            observeABContactPicked().collect { state ->
                zcashAddress.update { state.address }
                zcashAddressType.update { state.type }
            }
        }

        // Pre-fill address/amount/memo from external triggers (QR scan, ZIP321, tx replay)
        prefillSend()
            .onEach { data ->
                when (data) {
                    is PrefillSendData.FromAddressScan -> {
                        zcashAddress.update { data.address }
                        zcashAddressType.update {
                            if (data.address.isBlank()) null else validateAddress(data.address)
                        }
                    }

                    is PrefillSendData.All -> {
                        val addr = data.address.orEmpty()
                        zcashAddress.update { addr }
                        if (addr.isNotBlank()) {
                            zcashAddressType.update { validateAddress(addr) }
                        }
                        data.memos?.firstOrNull()?.let { memo -> memoText.update { memo } }
                        val fee = data.fee
                        val zatoshiAmount =
                            when {
                                fee == null -> data.amount
                                fee > data.amount -> data.amount
                                else -> data.amount - fee
                            }
                        val zecAmount =
                            BigDecimal(zatoshiAmount.value).divide(
                                BigDecimal("100000000"),
                                MathContext.DECIMAL128
                            )
                        onZecAmountChange(NumberTextFieldInnerState.fromAmount(zecAmount))
                    }
                }
            }.launchIn(viewModelScope)

        // Clear all fields when a proposal is cancelled
        observeClearSend()
            .onEach {
                zcashAddress.update { "" }
                zcashAddressType.update { null }
                zecAmountInner.update { NumberTextFieldInnerState() }
                fiatAmountInner.update { NumberTextFieldInnerState() }
                fiatWasLastEdited.update { false }
                memoText.update { "" }
                swapAddress.update { "" }
                swapContact.update { null }
                clearTokenAmount()
            }.launchIn(viewModelScope)

        preselectSwapAsset.observe().launchIn(viewModelScope)

        swapRepository.requestRefreshAssets()
    }

    // ── Public event handlers ─────────────────────────────────────────────────

    private fun currentFiatRate(): ZecFiatRate? =
        zecFiatRate(exchangeRateRepository.state.value, zecUsdPrice = null)

    fun onZecAmountChange(inner: NumberTextFieldInnerState) {
        zecAmountInner.update { inner }
        fiatWasLastEdited.update { false }
        swapMode.update { SwapMode.EXACT_INPUT }
        val rate = currentFiatRate() ?: return
        updateFiatAmount(inner.amount, rate)
    }

    fun onFiatAmountChange(inner: NumberTextFieldInnerState) {
        fiatAmountInner.update { inner }
        fiatWasLastEdited.update { true }
        swapMode.update { SwapMode.EXACT_INPUT }
        val rate = currentFiatRate() ?: return
        updateZecAmount(inner.amount, rate)
    }

    /**
     * The destination amount. Touching it pins the quote to this side and keeps it pinned: emptying
     * the field leaves the payment exact-output with no amount yet, so the button simply disables.
     * Handing authority back to the pay side is [onPayEstimateClick], and only that — a field that
     * refilled itself with an estimate the moment it was cleared could never be retyped.
     */
    fun onTokenAmountChange(inner: NumberTextFieldInnerState) {
        val asset = selectedAsset.value
        val decimals = asset?.decimals
        // Drop precision the destination chain cannot settle instead of rewriting what the user
        // sees mid-keystroke. NEAR truncates the same way when it builds the quote request, so a
        // finer amount could never reach the recipient anyway.
        if (decimals != null && inner.exceedsAssetDecimals(decimals)) return
        tokenAmountInner.update { inner }
        tokenFiatAmountInner.update { estimateUsdFromToken(inner.amount, asset?.usdPrice).toAmountState() }
        swapMode.update { SwapMode.EXACT_OUTPUT }
    }

    /**
     * The recipient's amount denominated in USD — the currency the destination asset is priced in,
     * so it lines up with an invoice. What we actually request is the token amount this converts to,
     * truncated to what the chain can settle.
     */
    fun onTokenFiatAmountChange(inner: NumberTextFieldInnerState) {
        val asset = selectedAsset.value
        tokenFiatAmountInner.update { inner }
        val decimals = asset?.decimals
        val token =
            estimateTokenFromUsd(inner.amount, asset?.usdPrice)
                ?.let { if (decimals == null) it else it.truncateToAssetDecimals(decimals) }
                // The field renders through stringResByDynamicNumber, which drops decimals it does
                // not need. Round the stored amount the same way so "they receive exactly X" quotes
                // the X on screen rather than a longer number behind it.
                ?.stripFractionsDynamically()
                ?.let { if (decimals == null) it else it.truncateToAssetDecimals(decimals) }
        tokenAmountInner.update { token.toAmountState() }
        swapMode.update { SwapMode.EXACT_OUTPUT }
    }

    /**
     * Hands authority back to the pay side, carrying the ZEC estimate over so the amount survives
     * the switch. This is the only way out of exact-output, which is why the pay row advertises
     * itself as tappable.
     */
    fun onPayEstimateClick() {
        val estimate =
            estimateZecFromToken(
                token = tokenAmountInner.value.amount,
                tokenUsdPrice = selectedAsset.value?.usdPrice,
                zecUsdPrice =
                    swapRepository.assets.value.zecAsset
                        ?.usdPrice
            )
        // onZecAmountChange already returns the form to exact-input; do it explicitly too so a
        // missing price (null estimate) still gets the user out.
        swapMode.update { SwapMode.EXACT_INPUT }
        if (estimate != null) onZecAmountChange(estimate.stripFractionsDynamically().toAmountState())
    }

    private fun clearTokenAmount() {
        tokenAmountInner.update { NumberTextFieldInnerState() }
        tokenFiatAmountInner.update { NumberTextFieldInnerState() }
        swapMode.update { SwapMode.EXACT_INPUT }
    }

    private fun updateFiatAmount(zec: BigDecimal?, rate: ZecFiatRate) {
        fiatAmountInner.value = zec?.let(rate::zecToFiat).toAmountState()
    }

    private fun updateZecAmount(fiat: BigDecimal?, rate: ZecFiatRate) {
        zecAmountInner.value = fiat?.let(rate::fiatToZec).toAmountState()
    }

    fun onAddressChange(new: String) {
        swapContact.update { null }
        val isSwap = selectedAsset.value?.let { it !is ZecSwapAsset } ?: false
        if (isSwap) {
            swapAddress.update { new }
        } else {
            zcashAddress.update { new }
            viewModelScope.launch {
                zcashAddressType.update {
                    if (new.isBlank()) null else validateAddress(new)
                }
            }
        }
    }

    fun onMemoChange(text: String) {
        memoText.update { text }
    }

    fun onAssetPickerClick() =
        navigationRouter.forward(SwapAssetPickerArgs(swapContact.value?.blockchain?.chainTicker))

    fun onAddressBookClick(isSwap: Boolean) =
        viewModelScope.launch {
            if (isSwap) {
                val selected = navigateToSelectSwapRecipient()
                if (selected != null) {
                    swapContact.update { selected }
                    swapAddress.update { "" }
                }
            } else {
                navigateToSelectRecipient()
            }
        }

    fun onDeleteSwapContactClick() = swapContact.update { null }

    fun onQrScannerClick() =
        viewModelScope.launch {
            val result = navigateToScanAddress()
            if (result != null) {
                navigationRouter.back()
                swapContact.update { null }
                swapAddress.update { result.address }
                zcashAddress.update { result.address }
                zcashAddressType.update { validateAddress(result.address) }
                // A scanned recipient starts a fresh payment: any exact-output amount typed for the
                // previous one no longer applies. ZIP-321 amounts are ZEC, so they pin the pay side.
                clearTokenAmount()
                if (result.amount != null) {
                    onZecAmountChange(NumberTextFieldInnerState.fromAmount(result.amount))
                }
            }
        }

    fun onPrimaryButtonClick(isSwap: Boolean) {
        if (isSwap) requestSwapQuoteClick() else createZecSendClick()
    }

    fun onBack() =
        viewModelScope.launch {
            if (isRequestingQuote.value) {
                isCancelStateVisible.update { true }
            } else if (isCancelStateVisible.value) {
                isCancelStateVisible.update { false }
                navigateToSwapQuoteIfAvailable { hideCancelBottomSheet() }
            } else {
                if (isCancelStateVisible.value) hideCancelBottomSheet()
                cancelSwap()
            }
        }

    fun onTryAgainClick() = swapRepository.requestRefreshAssets()

    fun onAmountSwap() {
        if (currentFiatRate() == null) return
        manualAmountSwap.update { current -> current?.not() ?: false }
    }

    /** Flips the destination field between the token and USD. */
    fun onDestinationCurrencySwap() =
        destinationCurrency.update { current ->
            when (current) {
                CurrencyType.TOKEN -> CurrencyType.FIAT
                CurrencyType.FIAT -> CurrencyType.TOKEN
            }
        }

    /**
     * [originUsd] is the USD value the slippage percentage is quoted against — the ZEC being spent
     * in exact-input, and the recipient's amount in exact-output (upstream's `getOriginFiatAmount`).
     */
    private fun onSlippageClick(originUsd: BigDecimal?, mode: SwapMode) =
        navigationRouter.forward(
            SwapSlippageArgs(
                fiatAmount = originUsd?.toPlainString(),
                mode = mode
            )
        )

    private fun onTopUpClick() = navigationRouter.forward(TopUpArgs)

    private fun onCrossPayInfoClick() = navigationRouter.forward(CrossPayInfoArgs)

    // ── Private submission helpers ────────────────────────────────────────────

    private fun requestSwapQuoteClick() {
        val addr = swapContact.value?.address ?: swapAddress.value
        if (addr.isBlank()) return
        val isExactOutput = swapMode.value == SwapMode.EXACT_OUTPUT
        val amount = (if (isExactOutput) tokenAmountInner else zecAmountInner).value.amount ?: return
        viewModelScope.launch {
            isRequestingQuote.update { true }
            if (isExactOutput) {
                requestSwapQuote.requestExactOutput(
                    amount = amount,
                    address = addr,
                    canNavigateToSwapQuote = { !isCancelStateVisible.value }
                )
            } else {
                requestSwapQuote.requestExactInput(
                    amount = amount,
                    address = addr,
                    canNavigateToSwapQuote = { !isCancelStateVisible.value }
                )
            }
            isRequestingQuote.update { false }
        }
    }

    private fun createZecSendClick() {
        val addr = zcashAddress.value
        val type = zcashAddressType.value
        val zecAmt = zecAmountInner.value.amount ?: return
        if (addr.isBlank() || type is AddressType.Invalid || type == null) return
        viewModelScope.launch {
            isRequestingQuote.update { true }
            // Read before the first suspension: the field is disabled from here on, but a keystroke
            // already in flight must not land in the memo of a proposal that is being built.
            val memoStr = if (type == AddressType.Transparent) "" else memoText.value
            try {
                val walletAddr = toWalletAddress(addr, type)
                val zatoshi = zecAmt.convertZecToZatoshi()
                val zecSend =
                    ZecSend(
                        destination = walletAddr,
                        amount = zatoshi,
                        memo = Memo(memoStr),
                        proposal = null
                    )
                createProposal(zecSend, fiatWasLastEdited.value)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                // createProposal handles navigation to error/review internally. Log the class
                // only — SDK validation messages can embed the typed recipient address.
                Twig.warn { "UnifiedSendVM: createZecSendClick failed (${e::class.simpleName})" }
            } finally {
                isRequestingQuote.update { false }
            }
        }
    }

    private suspend fun toWalletAddress(address: String, type: AddressType): WalletAddress =
        when (type) {
            AddressType.Unified -> WalletAddress.Unified.new(address)
            AddressType.Shielded -> WalletAddress.Unified.new(address)
            AddressType.Transparent -> WalletAddress.Transparent.new(address)
            AddressType.Tex -> WalletAddress.Tex.new(address)
            is AddressType.Invalid -> WalletAddress.Unified.new(address)
        }

    private fun onCancelSwapClick() =
        viewModelScope.launch {
            if (isCancelStateVisible.value) hideCancelBottomSheet()
            cancelSwap()
        }

    private fun onDismissCancelClick() =
        viewModelScope.launch {
            isCancelStateVisible.update { false }
            navigateToSwapQuoteIfAvailable { hideCancelBottomSheet() }
        }

    @Suppress("MagicNumber")
    private suspend fun hideCancelBottomSheet() {
        isCancelStateVisible.update { false }
        delay(350)
    }
}

/** The four amount fields plus the swap mode they imply. */
internal data class AmountInputs(
    val zec: NumberTextFieldInnerState,
    val fiat: NumberTextFieldInnerState,
    val token: NumberTextFieldInnerState,
    val tokenFiat: NumberTextFieldInnerState,
    val mode: SwapMode,
)

internal const val MAX_MEMO_BYTES = 512

/**
 * Everything the form is built from, plus the values derived once from it. Mirrors upstream's
 * `InternalState`: the view model assembles it out of its flows and [UnifiedSendVMMapper] turns it
 * into the rendered state.
 */
internal data class UnifiedSendInternalState(
    val selectedAsset: SwapAsset?,
    val amounts: AmountInputs,
    val zcashAddress: String,
    val zcashAddressType: AddressType?,
    val swapAddress: String,
    val contact: EnhancedABContact?,
    val memo: String,
    val swapAssets: SwapAssetsData,
    val slippage: BigDecimal,
    val account: WalletAccount?,
    val fiatRate: ZecFiatRate?,
    val isABHintVisible: Boolean,
    val isRequestingQuote: Boolean,
    val manualAmountSwap: Boolean?,
    val destinationCurrency: CurrencyType,
) {
    /**
     * Falls back to the ZEC asset from swap data when no explicit selection has been made yet. This
     * prevents the asset card from showing a Loading spinner while PreselectSwapAssetUseCase is
     * still running its coroutine.
     */
    val asset: SwapAsset? = selectedAsset ?: swapAssets.zecAsset

    val isSwap: Boolean = asset != null && asset !is ZecSwapAsset

    val mode: SwapMode = if (isSwap) amounts.mode else SwapMode.EXACT_INPUT

    val isExactOutput: Boolean = mode == SwapMode.EXACT_OUTPUT

    val zecUsdPrice: BigDecimal? = swapAssets.zecAsset?.usdPrice

    val fiatPrice: BigDecimal? = fiatRate?.pricePerZec

    val fiatCurrency: FiatCurrency? = fiatRate?.currency

    val tokenValue: BigDecimal? = amounts.token.amount

    /**
     * Exact-output: how much ZEC leaves the wallet is only known once NEAR quotes it, so the balance
     * check runs against a USD-price estimate. Deliberately un-padded, as upstream does — a user
     * close to their ceiling passes here and is caught by the InsufficientFundsException branch of
     * RequestSwapQuoteUseCase once the quote lands.
     */
    val zecValue: BigDecimal? =
        if (isExactOutput) {
            estimateZecFromToken(tokenValue, asset?.usdPrice, zecUsdPrice)
        } else {
            amounts.zec.amount
        }

    private val spendable = account?.spendableShieldedBalance

    private val zatoshi = zecValue?.convertZecToZatoshi()

    val hasFunds: Boolean = zatoshi == null || spendable == null || spendable >= zatoshi

    val hasZeroBalance: Boolean = spendable != null && spendable.value == 0L

    /** Whichever side the user typed into is the one that has to hold a usable number. */
    private val authoritativeAmount: BigDecimal? = if (isExactOutput) tokenValue else zecValue

    private val authoritativeField = if (isExactOutput) amounts.token else amounts.zec

    val hasAmount: Boolean = authoritativeAmount != null && authoritativeAmount > BigDecimal.ZERO

    val isAmountValid: Boolean = !authoritativeField.isError && hasAmount

    val isAddressValid: Boolean =
        if (isSwap) {
            (contact?.address ?: swapAddress).isNotBlank()
        } else {
            zcashAddressType != null &&
                zcashAddressType !is AddressType.Invalid &&
                zcashAddress.isNotEmpty()
        }

    val memoByteCount: Int = memo.toByteArray().size

    val isMemoValid: Boolean = zcashAddressType == AddressType.Transparent || memoByteCount <= MAX_MEMO_BYTES

    val showAmountError: Boolean = !hasFunds && hasAmount

    /** Everything a plain ZEC send needs before it can be reviewed. */
    val canSubmitZecSend: Boolean = isAddressValid && isAmountValid && hasFunds && isMemoValid

    /** Whether to point the user at Top Up: nothing to spend, or not enough for what they entered. */
    val needsTopUp: Boolean = hasZeroBalance || (isAmountValid && !hasFunds)

    /**
     * The USD figure the slippage percentage is quoted against — the recipient's amount in
     * exact-output, the ZEC being spent in exact-input. Null when the price it needs is missing, so
     * the slippage sheet drops the currency figure rather than promising "US$0.00".
     */
    val originUsd: BigDecimal? =
        if (isExactOutput) {
            estimateUsdFromToken(tokenValue, asset?.usdPrice)
        } else {
            estimateUsdFromToken(zecValue, zecUsdPrice)
        }

    val isAmountSwapped: Boolean = manualAmountSwap ?: (fiatPrice != null)
}
