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
import co.electriccoin.zcash.ui.design.component.AssetCardState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.InnerTextFieldState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.component.TextSelection
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.stripFractionsDynamically
import co.electriccoin.zcash.ui.screen.swap.SwapCancelState
import co.electriccoin.zcash.ui.screen.swap.SwapErrorFooterState
import co.electriccoin.zcash.ui.screen.swap.info.CrossPayInfoArgs
import co.electriccoin.zcash.ui.screen.swap.picker.SwapAssetPickerArgs
import co.electriccoin.zcash.ui.screen.swap.slippage.SwapSlippageArgs
import co.electriccoin.zcash.ui.screen.topup.TopUpArgs
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import co.electriccoin.zcash.ui.util.isServiceUnavailable
import io.ktor.client.plugins.ResponseException
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

    /** Swap: the destination-denominated amount. Non-empty means the user is paying an exact output. */
    private val tokenAmountInner = MutableStateFlow(NumberTextFieldInnerState())

    /**
     * Which side of the swap the user last typed into. Everything downstream — the quote screen,
     * the slippage copy, review, progress and history — already branches on the quote's own mode,
     * so this is the only place the app has to make the choice.
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

    // ── Derived flows ─────────────────────────────────────────────────────────

    private val selectedAsset = getSelectedSwapAsset.observe()

    /** Combined zcash recipient — reduces two flows to one for the main combine */
    private val zcashRecipient = combine(zcashAddress, zcashAddressType) { addr, type -> addr to type }

    private val swapAssetsWithRate =
        combine(getSwapAssetsUseCase.observe(), exchangeRateRepository.state) { assets, rate ->
            assets to rate
        }

    /** The three amount fields plus the mode they imply, reduced to one flow for the main combine. */
    private val amountInputs =
        combine(zecAmountInner, fiatAmountInner, tokenAmountInner, swapMode) { zec, fiat, token, mode ->
            AmountInputs(zec = zec, fiat = fiat, token = token, mode = mode)
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

    private val coreState =
        co.electriccoin.zcash.ui.design.util.combine(
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
        ) {
            asset,
            amounts,
            (zcashAddr, zcashType),
            swapAddr,
            contact,
            memo,
            (swapAssets, exchangeRate),
            slippage,
            account,
            abHintVisible,
            requesting,
            ->
            // Fall back to the ZEC asset from swap data when no explicit selection has been
            // made yet. This prevents the asset card from showing a Loading spinner while
            // PreselectSwapAssetUseCase is still running its coroutine.
            val effectiveAsset = asset ?: swapAssets.zecAsset
            val isSwap = effectiveAsset != null && effectiveAsset !is ZecSwapAsset
            val zecUsdPrice = swapAssets.zecAsset?.usdPrice
            val fiatRate = zecFiatRate(exchangeRate, zecUsdPrice = null)
            val fiatPrice = fiatRate?.pricePerZec
            val fiatCurrency = fiatRate?.currency
            val spendable = account?.spendableShieldedBalance
            val mode = if (isSwap) amounts.mode else SwapMode.EXACT_INPUT
            val tokenValue = amounts.token.amount
            // Exact-output: how much ZEC leaves the wallet is only known once NEAR quotes it, so
            // the balance check runs against a USD-price estimate. Deliberately un-padded, as
            // upstream does — a user close to their ceiling passes here and is caught by the
            // InsufficientFundsException branch of RequestSwapQuoteUseCase once the quote lands.
            val zecValue =
                if (mode == SwapMode.EXACT_OUTPUT) {
                    estimateZecFromToken(tokenValue, effectiveAsset?.usdPrice, zecUsdPrice)
                } else {
                    amounts.zec.amount
                }
            val zatoshi = zecValue?.convertZecToZatoshi()
            val hasFunds = zatoshi == null || spendable == null || spendable >= zatoshi
            val hasZeroBalance = spendable != null && spendable.value == 0L

            buildFormState(
                isSwap = isSwap,
                mode = mode,
                asset = effectiveAsset,
                zecAmount = amounts.zec,
                fiatAmount = amounts.fiat,
                tokenAmount = amounts.token,
                zcashAddr = zcashAddr,
                zcashType = zcashType,
                swapAddr = swapAddr,
                contact = contact,
                memo = memo,
                swapAssets = swapAssets,
                slippage = slippage,
                zecUsdPrice = zecUsdPrice,
                fiatPrice = fiatPrice,
                fiatCurrency = fiatCurrency,
                zecValue = zecValue,
                tokenValue = tokenValue,
                hasFunds = hasFunds,
                hasZeroBalance = hasZeroBalance,
                abHintVisible = abHintVisible,
                isRequesting = requesting,
            )
        }

    val state =
        coreState
            .combine(manualAmountSwap) { form, manual ->
                form.copy(isAmountSwapped = manual ?: form.isAmountSwapped, onAmountSwap = ::onAmountSwap)
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
     * The destination amount. Typing here makes this the side the quote is pinned to; emptying the
     * field hands authority back to the ZEC amount above it.
     */
    fun onTokenAmountChange(inner: NumberTextFieldInnerState) {
        val decimals = selectedAsset.value?.decimals
        // Drop precision the destination chain cannot settle instead of rewriting what the user
        // sees mid-keystroke. NEAR truncates the same way when it builds the quote request, so a
        // finer amount could never reach the recipient anyway.
        if (decimals != null && inner.exceedsAssetDecimals(decimals)) return
        tokenAmountInner.update { inner }
        swapMode.update { if (inner.isBlankInput()) SwapMode.EXACT_INPUT else SwapMode.EXACT_OUTPUT }
    }

    /**
     * Hands authority back to the pay side, carrying the ZEC estimate over so the amount survives
     * the switch. Without this the only way out of exact-output would be to empty the destination
     * field, which the pay row — now just text — gives no hint of.
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
        swapMode.update { SwapMode.EXACT_INPUT }
    }

    private fun updateFiatAmount(zec: BigDecimal?, rate: ZecFiatRate) {
        fiatAmountInner.value = zec?.let(rate::zecToFiat).toAmountState()
    }

    private fun updateZecAmount(fiat: BigDecimal?, rate: ZecFiatRate) {
        zecAmountInner.value = fiat?.let(rate::fiatToZec).toAmountState()
    }

    private fun BigDecimal?.toAmountState(): NumberTextFieldInnerState =
        this?.let { amount ->
            NumberTextFieldInnerState(
                innerTextFieldState =
                    InnerTextFieldState(
                        value = stringResByDynamicNumber(amount, includeGroupingSeparator = false),
                        selection = TextSelection.End,
                    ),
                amount = amount,
                lastValidAmount = amount,
            )
        } ?: NumberTextFieldInnerState()

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
            try {
                val walletAddr = toWalletAddress(addr, type)
                val zatoshi = zecAmt.convertZecToZatoshi()
                val memoStr = if (type == AddressType.Transparent) "" else memoText.value
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

    private fun onTopUpClick() = navigationRouter.forward(TopUpArgs)

    private fun onCrossPayInfoClick() = navigationRouter.forward(CrossPayInfoArgs)

    // ── State builder ─────────────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun buildFormState(
        isSwap: Boolean,
        mode: SwapMode,
        asset: SwapAsset?,
        zecAmount: NumberTextFieldInnerState,
        fiatAmount: NumberTextFieldInnerState,
        tokenAmount: NumberTextFieldInnerState,
        zcashAddr: String,
        zcashType: AddressType?,
        swapAddr: String,
        contact: EnhancedABContact?,
        memo: String,
        swapAssets: SwapAssetsData,
        slippage: BigDecimal,
        zecUsdPrice: BigDecimal?,
        fiatPrice: BigDecimal?,
        fiatCurrency: FiatCurrency?,
        zecValue: BigDecimal?,
        tokenValue: BigDecimal?,
        hasFunds: Boolean,
        hasZeroBalance: Boolean,
        abHintVisible: Boolean,
        isRequesting: Boolean,
    ): UnifiedSendState {
        val isExactOutput = mode == SwapMode.EXACT_OUTPUT
        // Whichever side the user typed into is the one that has to hold a usable number.
        val authoritativeAmount = if (isExactOutput) tokenValue else zecValue
        val authoritativeField = if (isExactOutput) tokenAmount else zecAmount
        val hasAmount = authoritativeAmount != null && authoritativeAmount > BigDecimal.ZERO
        val isAmountValid = !authoritativeField.isError && hasAmount
        val isAddressValid =
            if (isSwap) {
                (contact?.address ?: swapAddr).isNotBlank()
            } else {
                zcashType != null && zcashType !is AddressType.Invalid && zcashAddr.isNotEmpty()
            }
        val isMemoValid = zcashType == AddressType.Transparent || memo.toByteArray().size <= 512

        val showAmountError = !hasFunds && hasAmount
        val theyReceive =
            buildTheyReceiveState(
                isSwap = isSwap,
                isExactOutput = isExactOutput,
                asset = asset,
                tokenAmount = tokenAmount,
                zecValue = zecValue,
                zecUsdPrice = zecUsdPrice,
                showAmountError = showAmountError,
                isRequesting = isRequesting,
            )
        // The USD figure the slippage percentage is quoted against. In exact-output it comes off
        // the recipient's amount rather than the ZEC estimate, so it is exact rather than derived.
        val originUsd =
            if (isExactOutput) {
                estimateUsdFromToken(tokenValue, asset?.usdPrice)
            } else {
                zecValue?.multiply(zecUsdPrice ?: BigDecimal.ZERO, MathContext.DECIMAL128)
            }
        val slippageLabel: StringResource? =
            if (isSwap) {
                stringResByNumber(slippage, minDecimals = 0) + stringRes("%")
            } else {
                null
            }

        return UnifiedSendState(
            asset = buildAssetState(asset, isRequesting),
            address =
                TextFieldState(
                    value = stringRes(if (isSwap) swapAddr else zcashAddr),
                    error =
                        if (!isSwap && zcashAddr.isNotEmpty() && zcashType is AddressType.Invalid) {
                            stringRes(R.string.send_address_invalid)
                        } else {
                            null
                        },
                    onValueChange = ::onAddressChange,
                    isEnabled = !isRequesting,
                ),
            addressPlaceholder =
                if (isSwap && asset != null) {
                    stringRes(
                        co.electriccoin.zcash.ui.design.R.string.general_enter_address_partial,
                        asset.chainName
                    )
                } else {
                    stringRes(R.string.unified_send_address_placeholder)
                },
            abContact =
                if (contact == null) {
                    null
                } else {
                    ChipButtonState(
                        text = stringRes(contact.contact.name),
                        onClick = ::onDeleteSwapContactClick,
                        endIcon = co.electriccoin.zcash.ui.design.R.drawable.ic_chip_close,
                        isEnabled = !isRequesting,
                    )
                },
            abButton =
                IconButtonState(
                    icon = R.drawable.send_address_book,
                    contentDescription = stringRes(R.string.send_address_book_content_description),
                    onClick = { onAddressBookClick(isSwap) },
                    isEnabled = !isRequesting
                ),
            qrButton =
                IconButtonState(
                    icon = R.drawable.qr_code_icon,
                    contentDescription = stringRes(R.string.send_scan_content_description),
                    onClick = ::onQrScannerClick,
                    isEnabled = !isRequesting
                ),
            infoButton =
                if (isSwap) {
                    IconButtonState(
                        icon = R.drawable.ic_help,
                        contentDescription = stringRes(R.string.unified_send_crosspay_info),
                        onClick = ::onCrossPayInfoClick,
                        isEnabled = !isRequesting
                    )
                } else {
                    null
                },
            isABHintVisible = abHintVisible,
            zecAmount =
                NumberTextFieldState(
                    innerState = zecAmount,
                    onValueChange = ::onZecAmountChange,
                    isEnabled = !isRequesting,
                    explicitError = if (showAmountError) stringRes("") else null
                ),
            fiatAmount =
                NumberTextFieldState(
                    innerState = fiatAmount,
                    onValueChange = ::onFiatAmountChange,
                    isEnabled = !isRequesting && fiatPrice != null,
                    explicitError = if (showAmountError) stringRes("") else null
                ),
            fiatCurrency = fiatCurrency,
            isAmountSwapped = fiatPrice != null,
            onAmountSwap = ::onAmountSwap,
            amountError =
                if (showAmountError) {
                    stringRes(R.string.send_amount_insufficient_balance)
                } else {
                    null
                },
            theyReceive = theyReceive,
            payEstimate = if (isExactOutput) buildPayEstimate(zecValue, isRequesting) else null,
            slippage = slippageLabel,
            onSlippageClick =
                if (isSwap) {
                    { onSlippageClick(originUsd, mode) }
                } else {
                    null
                },
            memo =
                if (isSwap) {
                    null
                } else {
                    MemoFieldState.Editable(
                        text = memo,
                        byteCount = memo.toByteArray().size,
                        maxBytes = 512,
                        isEnabled = zcashType != AddressType.Transparent,
                        onValueChange = ::onMemoChange
                    )
                },
            amountErrorFooter = null,
            errorFooter = buildErrorFooter(swapAssets),
            infoFooter =
                if (!isSwap && (hasZeroBalance || (hasAmount && isAmountValid && !hasFunds))) {
                    stringRes(R.string.top_up_balance_subtitle)
                } else {
                    null
                },
            onBack = ::onBack,
            primaryButton =
                buildPrimaryButton(
                    isSwap = isSwap,
                    isRequesting = isRequesting,
                    swapAssets = swapAssets,
                    isAddressValid = isAddressValid,
                    isAmountValid = isAmountValid,
                    hasAmount = hasAmount,
                    hasFunds = hasFunds,
                    hasZeroBalance = hasZeroBalance,
                    isMemoValid = isMemoValid,
                ),
        )
    }

    /**
     * The destination row. The field is editable in both modes: in exact-input it shows the
     * client-side estimate and typing over it flips the form to exact-output, which is the only
     * way into "the recipient gets exactly X".
     */
    private fun buildTheyReceiveState(
        isSwap: Boolean,
        isExactOutput: Boolean,
        asset: SwapAsset?,
        tokenAmount: NumberTextFieldInnerState,
        zecValue: BigDecimal?,
        zecUsdPrice: BigDecimal?,
        showAmountError: Boolean,
        isRequesting: Boolean,
    ): TheyReceiveState? {
        if (!isSwap || asset == null) return null
        val innerState =
            if (isExactOutput) {
                tokenAmount
            } else {
                // Round to what the field will actually render, so tapping in and adopting the
                // estimate quotes exactly the number the user was looking at.
                estimateTokenFromZec(zecValue, zecUsdPrice, asset.usdPrice)
                    ?.truncateToAssetDecimals(asset.decimals)
                    ?.stripFractionsDynamically()
                    ?.truncateToAssetDecimals(asset.decimals)
                    .toAmountState()
            }
        return TheyReceiveState(
            label =
                stringRes(
                    if (isExactOutput) {
                        R.string.unified_send_they_receive_exact
                    } else {
                        R.string.unified_send_they_receive_approx
                    }
                ),
            ticker = asset.tokenTicker,
            amount =
                NumberTextFieldState(
                    innerState = innerState,
                    onValueChange = ::onTokenAmountChange,
                    isEnabled = !isRequesting,
                    explicitError = if (isExactOutput && showAmountError) stringRes("") else null
                ),
            fiatEquivalent =
                if (isExactOutput) {
                    estimateUsdFromToken(tokenAmount.amount, asset.usdPrice)?.let { usd ->
                        stringRes(
                            R.string.unified_send_estimated_equivalent,
                            stringResByDynamicNumber(usd),
                            FiatCurrency.USD.code
                        )
                    }
                } else {
                    null
                },
        )
    }

    /** The greyed "≈ 0.42 ZEC" that stands in for the pay fields while exact-output is in force. */
    private fun buildPayEstimate(zecValue: BigDecimal?, isRequesting: Boolean): PayEstimateState =
        PayEstimateState(
            text =
                stringRes(
                    R.string.unified_send_estimated_equivalent,
                    // Prices have not loaded yet. The payment still works — NEAR prices it — so
                    // say the cost is unknown rather than dropping the "I'll pay" row entirely.
                    zecValue?.let { stringResByDynamicNumber(it) } ?: stringRes("—"),
                    CURRENCY_TICKER
                ),
            onClick =
                if (isRequesting) {
                    {}
                } else {
                    ::onPayEstimateClick
                }
        )

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

    private fun buildAssetState(asset: SwapAsset?, isRequesting: Boolean): AssetCardState =
        if (asset == null) {
            AssetCardState.Loading(onClick = ::onAssetPickerClick, isEnabled = !isRequesting)
        } else {
            AssetCardState.Data(
                token = stringRes(asset.tokenTicker),
                chain = if (asset is ZecSwapAsset) null else asset.chainName,
                isSingleLine = true,
                bigIcon = asset.tokenIcon,
                smallIcon = if (asset is ZecSwapAsset) null else asset.chainIcon,
                onClick = ::onAssetPickerClick,
                isEnabled = !isRequesting,
            )
        }

    private fun buildErrorFooter(swapAssets: SwapAssetsData): SwapErrorFooterState? {
        if (swapAssets.error == null) return null
        val isUnavailable =
            swapAssets.error is ResponseException &&
                swapAssets.error.response.status
                    .isServiceUnavailable()
        return SwapErrorFooterState(
            title =
                if (isUnavailable) {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.general_service_unavailable)
                } else {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.general_unexpected_error)
                },
            subtitle =
                if (isUnavailable) {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.general_please_try_again)
                } else {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.general_check_connection)
                }
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildPrimaryButton(
        isSwap: Boolean,
        isRequesting: Boolean,
        swapAssets: SwapAssetsData,
        isAddressValid: Boolean,
        isAmountValid: Boolean,
        hasAmount: Boolean,
        hasFunds: Boolean,
        hasZeroBalance: Boolean,
        isMemoValid: Boolean,
    ): PrimaryButtonState {
        // Service unavailable blocks button
        if (isSwap && swapAssets.error is ResponseException &&
            swapAssets.error.response.status
                .isServiceUnavailable()
        ) {
            return PrimaryButtonState.Disabled
        }

        // Zero balance in ZEC mode → always show Top Up (before any amount is entered)
        if (!isSwap && hasZeroBalance) {
            return PrimaryButtonState.TopUp(onClick = ::onTopUpClick)
        }

        // Insufficient funds → Top Up
        if (hasAmount && isAmountValid && !hasFunds) {
            return PrimaryButtonState.TopUp(onClick = ::onTopUpClick)
        }

        return if (isSwap) {
            when {
                swapAssets.error != null -> {
                    PrimaryButtonState.Review(
                        isLoading = swapAssets.isLoading && swapAssets.data == null,
                        onClick = ::onTryAgainClick
                    )
                }

                swapAssets.data != null && isAddressValid && isAmountValid && !isRequesting -> {
                    PrimaryButtonState.Review(
                        isLoading = isRequesting,
                        onClick = { onPrimaryButtonClick(true) }
                    )
                }

                else -> {
                    PrimaryButtonState.Disabled
                }
            }
        } else {
            if (isAddressValid && isAmountValid && hasFunds && isMemoValid) {
                PrimaryButtonState.Review(
                    isLoading = isRequesting,
                    onClick = { onPrimaryButtonClick(false) }
                )
            } else {
                PrimaryButtonState.Disabled
            }
        }
    }
}

/** The three amount fields plus the swap mode they imply. */
private data class AmountInputs(
    val zec: NumberTextFieldInnerState,
    val fiat: NumberTextFieldInnerState,
    val token: NumberTextFieldInnerState,
    val mode: SwapMode,
)
