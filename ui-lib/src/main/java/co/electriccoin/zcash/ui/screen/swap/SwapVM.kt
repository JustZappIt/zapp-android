package co.electriccoin.zcash.ui.screen.swap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.AddFundsPanelState
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.Funding
import co.electriccoin.zcash.ui.common.usecase.GetSelectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSlippageUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWholeBalanceSendableUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectABSwapRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapInfoUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapQuoteIfAvailableUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveFundingUseCase
import co.electriccoin.zcash.ui.common.usecase.PreselectSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.toZecExact
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.InnerTextFieldState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.TextSelection
import co.electriccoin.zcash.ui.design.component.zapp.AmountPreset
import co.electriccoin.zcash.ui.design.component.zapp.AmountPresetsState
import co.electriccoin.zcash.ui.design.util.combine
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicNumber
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.swap.Mode.SWAP_FROM_ZEC
import co.electriccoin.zcash.ui.screen.swap.Mode.SWAP_INTO_ZEC
import co.electriccoin.zcash.ui.screen.swap.info.SwapRefundAddressInfoArgs
import co.electriccoin.zcash.ui.screen.swap.picker.SwapAssetPickerArgs
import co.electriccoin.zcash.ui.screen.swap.slippage.SwapSlippageArgs
import co.electriccoin.zcash.ui.screen.topup.TopUpArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

@Suppress("TooManyFunctions")
internal class SwapVM(
    getSlippage: GetSlippageUseCase,
    getSelectedSwapAsset: GetSelectedSwapAssetUseCase,
    getSwapAssetsUseCase: GetSwapAssetsUseCase,
    getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    preselectSwapAsset: PreselectSwapAssetUseCase,
    private val swapRepository: SwapRepository,
    private val navigateToSwapInfo: NavigateToSwapInfoUseCase,
    private val cancelSwap: CancelSwapUseCase,
    private val navigationRouter: NavigationRouter,
    private val requestSwapQuote: RequestSwapQuoteUseCase,
    private val navigateToSwapQuoteIfAvailable: NavigateToSwapQuoteIfAvailableUseCase,
    private val exactInputVMMapper: ExactInputVMMapper,
    private val navigateToScanAddress: NavigateToScanGenericAddressUseCase,
    private val navigateToSelectSwapRecipient: NavigateToSelectABSwapRecipientUseCase,
    private val getWholeBalanceSendable: GetWholeBalanceSendableUseCase,
    observeFunding: ObserveFundingUseCase,
) : ViewModel() {
    private val mode = MutableStateFlow(SWAP_INTO_ZEC)

    private val currencyType: MutableStateFlow<CurrencyType> = MutableStateFlow(CurrencyType.TOKEN)

    private val addressText: MutableStateFlow<String> = MutableStateFlow("")

    private val amountText: MutableStateFlow<NumberTextFieldInnerState> = MutableStateFlow(NumberTextFieldInnerState())

    private val isRequestingQuote = MutableStateFlow(false)

    private val isCancelStateVisible = MutableStateFlow(false)

    private var selectedContact: MutableStateFlow<EnhancedABContact?> = MutableStateFlow(null)

    private val receivingAddressType: MutableStateFlow<ReceivingAddressType> =
        MutableStateFlow(ReceivingAddressType.UNIFIED)

    /** The 20% / 50% / ALL fill behind the ZEC being swapped, until the user types over it. */
    private val amountPreset = MutableStateFlow<AmountPreset?>(null)

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
                                text = stringRes(R.string.swap_quote_cancel_payment),
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

    private val innerState =
        combine(
            addressText,
            amountText,
            getSelectedSwapAsset.observe(),
            getSlippage.observe(),
            currencyType,
            getSwapAssetsUseCase.observe(),
            isRequestingQuote,
            selectedContact,
            getSelectedWalletAccount.observe(),
            mode,
            receivingAddressType,
        ) {
            address,
            amount,
            asset,
            slippage,
            currencyType,
            swapAssets,
            isRequestingQuote,
            selectedContact,
            account,
            mode,
            receivingAddressType,
            ->
            InternalStateImpl(
                swapAsset = asset,
                currencyType = currencyType,
                amountTextState = amount,
                addressText = address,
                slippage = slippage,
                swapAssets = swapAssets,
                isRequestingQuote = isRequestingQuote,
                selectedContact = selectedContact,
                account = account,
                mode = mode,
                isEphemeralAddressLocked = false,
                receivingAddressType = receivingAddressType,
            )
        }

    private val latestInner = innerState.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state =
        combine(latestInner.filterNotNull(), amountPreset, observeFunding.zec()) { innerState, preset, funding ->
            createState(innerState, preset).copy(
                addFunds =
                    AddFundsPanelState(
                        body = stringRes(R.string.add_funds_panel_swap),
                        onAddFunds = ::onTopUpClick,
                    ).takeIf { funding == Funding.EMPTY && innerState.mode == SWAP_FROM_ZEC },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    init {
        preselectSwapAsset
            .observe()
            .launchIn(viewModelScope)
    }

    private fun createState(
        innerState: InternalStateImpl,
        preset: AmountPreset?,
    ): SwapState =
        exactInputVMMapper
            .createState(
                internalState = innerState,
                onBack = ::onBack,
                onSwapInfoClick = ::onSwapInfoClick,
                onSwapAssetPickerClick = ::onSwapAssetPickerClick,
                onSwapCurrencyTypeClick = ::onSwapCurrencyTypeClick,
                onSlippageClick = ::onSlippageClick,
                onRequestSwapQuoteClick = ::onRequestSwapQuoteClick,
                onTryAgainClick = ::onTryAgainClick,
                onAddressChange = ::onAddressChange,
                onTextFieldChange = ::onTextFieldChange,
                onQrCodeScannerClick = ::onQrCodeScannerClick,
                onAddressBookClick = ::onAddressBookClick,
                onDeleteSelectedContactClick = ::onDeleteSelectedContactClick,
                onBalanceButtonClick = ::onBalanceButtonClick,
                onChangeButtonClick = ::onChangeButtonClick,
                onAddressClick = ::onAddressClick,
                onTopUpClick = ::onTopUpClick,
                onChangeReceivingAddress = ::onChangeReceivingAddressClick
            ).let { state ->
                val canPreset = innerState.mode == SWAP_FROM_ZEC && innerState.totalSpendableBalance.value > 0
                state.copy(
                    // The presets row stands in for the field's own Max.
                    amountTextField = state.amountTextField.copy(max = null),
                    presets =
                        AmountPresetsState(
                            selected = preset,
                            isEnabled = !innerState.isRequestingQuote,
                            onSelect = ::onAmountPreset,
                        ).takeIf { canPreset },
                )
            }

    /**
     * A share of the spendable ZEC, in whichever currency the field shows. ALL's fee comes out when the quote is
     * asked for, so the field shows the whole balance.
     */
    private fun onAmountPreset(preset: AmountPreset) {
        val inner = latestInner.value?.takeIf { it.mode == SWAP_FROM_ZEC } ?: return
        val zec = Zatoshi(preset.of(inner.totalSpendableBalance.value)).toZecExact()
        val shown =
            when (inner.currencyType) {
                CurrencyType.TOKEN -> {
                    zec
                }

                CurrencyType.FIAT -> {
                    inner.swapAssets.zecAsset?.usdPrice?.let { price ->
                        zec.multiply(price, MathContext.DECIMAL128).setScale(FIAT_DECIMALS, RoundingMode.DOWN)
                    }
                }
            } ?: return
        amountText.update { NumberTextFieldInnerState.fromAmount(shown) }
        amountPreset.update { preset }
    }

    private fun onBalanceButtonClick() {
        // navigationRouter.forward(SpendableBalanceArgs)
    }

    private fun onTopUpClick() = navigationRouter.forward(TopUpArgs)

    private fun onChangeButtonClick() {
        amountPreset.update { null }
        mode.update {
            when (it) {
                SWAP_FROM_ZEC -> SWAP_INTO_ZEC
                SWAP_INTO_ZEC -> SWAP_FROM_ZEC
            }
        }
    }

    private fun onChangeReceivingAddressClick() {
        receivingAddressType.update {
            when (it) {
                ReceivingAddressType.UNIFIED -> ReceivingAddressType.TRANSPARENT
                ReceivingAddressType.TRANSPARENT -> ReceivingAddressType.UNIFIED
            }
        }
    }

    private fun onAddressClick() = navigationRouter.forward(SwapRefundAddressInfoArgs)

    private fun onDeleteSelectedContactClick() = selectedContact.update { null }

    private fun onTryAgainClick() = swapRepository.requestRefreshAssets()

    private fun onAddressBookClick() =
        viewModelScope.launch {
            val selected = navigateToSelectSwapRecipient()

            if (selected != null) {
                selectedContact.update { selected }
                addressText.update { "" }
                preselectChain(selected)
            }
        }

    private fun preselectChain(selected: EnhancedABContact) {
        if (mode.value == SWAP_INTO_ZEC) {
            val selectedChainTicker = selected.blockchain?.chainTicker
            val currentAsset = swapRepository.selectedAsset.value
            val currentChainTicker = currentAsset?.chainTicker

            // Only proceed if chain ticker changed
            if (selectedChainTicker != null &&
                !selectedChainTicker.equals(currentChainTicker, ignoreCase = true)
            ) {
                val swapAssets = swapRepository.assets.value.data
                val matchingAssets =
                    swapAssets
                        ?.filter { asset ->
                            asset.chainTicker.equals(selectedChainTicker, ignoreCase = true)
                        }.orEmpty()

                if (matchingAssets.size == 1) {
                    swapRepository.select(matchingAssets.first())
                } else {
                    swapRepository.select(null)
                }
            }
        }
    }

    private fun onQrCodeScannerClick() =
        viewModelScope.launch {
            val result = navigateToScanAddress()
            if (result != null) {
                navigationRouter.back()
                selectedContact.update { null }
                addressText.update { result.address }
                if (result.amount != null) {
                    amountPreset.update { null }
                    amountText.update { NumberTextFieldInnerState.fromAmount(result.amount) }
                }
            }
        }

    private fun onSlippageClick(fiatAmount: BigDecimal?) =
        navigationRouter.forward(
            SwapSlippageArgs(
                fiatAmount = fiatAmount?.toPlainString(),
                mode = SwapMode.EXACT_INPUT
            )
        )

    private fun onBack() =
        viewModelScope.launch {
            if (isRequestingQuote.value) {
                isCancelStateVisible.update { true }
            } else if (isCancelStateVisible.value) {
                isCancelStateVisible.update { false }
                navigateToSwapQuoteIfAvailable { hideCancelBottomSheet() }
            } else {
                if (isCancelStateVisible.value) {
                    hideCancelBottomSheet()
                }
                cancelSwap()
            }
        }

    private fun onCancelSwapClick() =
        viewModelScope.launch {
            if (isCancelStateVisible.value) {
                hideCancelBottomSheet()
            }
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

    private fun onSwapCurrencyTypeClick(newTextFieldAmount: BigDecimal?) {
        amountText.update {
            NumberTextFieldInnerState(
                innerTextFieldState =
                    InnerTextFieldState(
                        value =
                            newTextFieldAmount
                                ?.let { stringResByDynamicNumber(it, includeGroupingSeparator = false) }
                                ?: stringRes(""),
                        selection = TextSelection.End
                    ),
                amount = newTextFieldAmount,
                lastValidAmount = newTextFieldAmount
            )
        }
        currencyType.update {
            when (it) {
                CurrencyType.TOKEN -> CurrencyType.FIAT
                CurrencyType.FIAT -> CurrencyType.TOKEN
            }
        }
    }

    private fun onTextFieldChange(new: NumberTextFieldInnerState) {
        amountPreset.update { null }
        amountText.update { new }
    }

    private fun onRequestSwapQuoteClick(amount: BigDecimal, address: String) =
        viewModelScope.launch {
            isRequestingQuote.update { true }
            when (mode.value) {
                SWAP_FROM_ZEC -> {
                    // ALL swaps the balance less the deposit's fee; NEAR deposit addresses are transparent.
                    val sent =
                        if (amountPreset.value == AmountPreset.ALL) {
                            wholeBalanceOrNull(amount)?.toZecExact() ?: run {
                                isRequestingQuote.update { false }
                                return@launch
                            }
                        } else {
                            amount
                        }
                    requestSwapQuote.requestExactInput(
                        amount = sent,
                        address = address,
                        canNavigateToSwapQuote = { !isCancelStateVisible.value }
                    )
                }

                SWAP_INTO_ZEC -> {
                    requestSwapQuote.requestExactInputIntoZec(
                        amount = amount,
                        refundAddress = address,
                        canNavigateToSwapQuote = { !isCancelStateVisible.value }
                    )
                }
            }
            isRequestingQuote.update { false }
        }

    /**
     * The whole balance less a NEAR deposit's fee — deposit addresses are transparent — or null once shown there
     * isn't enough. Any other failure goes ahead with [shown], so the quote's own errors say what's wrong.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun wholeBalanceOrNull(shown: BigDecimal): Zatoshi? =
        try {
            getWholeBalanceSendable { it.transparent.address }
        } catch (_: InsufficientFundsException) {
            navigationRouter.forward(InsufficientFundsArgs)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.warn { "SwapVM: no whole-balance amount (${e::class.simpleName})" }
            shown.convertZecToZatoshi()
        }

    private fun onSwapInfoClick() = navigateToSwapInfo()

    private fun onAddressChange(new: String) {
        selectedContact.update { null }
        addressText.update { new }
    }

    private fun onSwapAssetPickerClick() =
        navigationRouter.forward(
            SwapAssetPickerArgs(chainTicker = selectedContact.value?.blockchain?.chainTicker)
        )
}

internal enum class CurrencyType { TOKEN, FIAT }

internal enum class Mode { SWAP_FROM_ZEC, SWAP_INTO_ZEC }

internal enum class ReceivingAddressType { UNIFIED, TRANSPARENT }

internal interface InternalState {
    val account: WalletAccount?
    val swapAsset: SwapAsset?
    val currencyType: CurrencyType
    val amountTextState: NumberTextFieldInnerState
    val addressText: String
    val slippage: BigDecimal
    val swapAssets: SwapAssetsData
    val isRequestingQuote: Boolean
    val selectedContact: EnhancedABContact?
    val mode: Mode
    val isEphemeralAddressLocked: Boolean
    val receivingAddressType: ReceivingAddressType

    val totalSpendableBalance: Zatoshi
        get() = account?.spendableShieldedBalance ?: Zatoshi(0)
}

internal data class InternalStateImpl(
    override val account: WalletAccount?,
    override val swapAsset: SwapAsset?,
    override val currencyType: CurrencyType,
    override val amountTextState: NumberTextFieldInnerState,
    override val addressText: String,
    override val slippage: BigDecimal,
    override val swapAssets: SwapAssetsData,
    override val isRequestingQuote: Boolean,
    override val selectedContact: EnhancedABContact?,
    override val mode: Mode,
    override val isEphemeralAddressLocked: Boolean,
    override val receivingAddressType: ReceivingAddressType,
) : InternalState

private const val FIAT_DECIMALS = 2
