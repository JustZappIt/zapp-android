package co.electriccoin.zcash.ui.screen.unifiedsend

import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.design.component.AssetCardState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.stripFractionsDynamically
import co.electriccoin.zcash.ui.screen.swap.CurrencyType
import co.electriccoin.zcash.ui.screen.swap.SwapErrorFooterState
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import co.electriccoin.zcash.ui.util.isServiceUnavailable
import io.ktor.client.plugins.ResponseException
import java.math.BigDecimal

/**
 * Turns [UnifiedSendInternalState] into the state the view renders, in the shape upstream uses for
 * every swap-family screen: the view model owns the flows and the handlers, the mapper owns the
 * translation. Upstream splits this across a public `InternalState` interface and a private copy
 * inside the mapper; both live in this package, so one data class carries the derived values instead.
 */
@Suppress("TooManyFunctions", "LongParameterList")
internal class UnifiedSendVMMapper {
    @Suppress("CyclomaticComplexMethod")
    fun createState(
        state: UnifiedSendInternalState,
        onBack: () -> Unit,
        onAssetPickerClick: () -> Unit,
        onAddressChange: (String) -> Unit,
        onAddressBookClick: (Boolean) -> Unit,
        onQrScannerClick: () -> Unit,
        onDeleteSwapContactClick: () -> Unit,
        onCrossPayInfoClick: () -> Unit,
        onZecAmountChange: (NumberTextFieldInnerState) -> Unit,
        onFiatAmountChange: (NumberTextFieldInnerState) -> Unit,
        onTokenAmountChange: (NumberTextFieldInnerState) -> Unit,
        onTokenFiatAmountChange: (NumberTextFieldInnerState) -> Unit,
        onPayEstimateClick: () -> Unit,
        onDestinationCurrencySwap: () -> Unit,
        onAmountSwap: () -> Unit,
        onMemoChange: (String) -> Unit,
        onSlippageClick: (BigDecimal?, SwapMode) -> Unit,
        onPrimaryButtonClick: (Boolean) -> Unit,
        onTryAgainClick: () -> Unit,
        onTopUpClick: () -> Unit,
    ): UnifiedSendState {
        val isSwap = state.isSwap
        val isRequesting = state.isRequestingQuote

        return UnifiedSendState(
            asset = createAssetState(state, onAssetPickerClick),
            address = createAddressState(state, onAddressChange),
            addressPlaceholder = createAddressPlaceholder(state),
            abContact = createContactChip(state, onDeleteSwapContactClick),
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
                    onClick = onQrScannerClick,
                    isEnabled = !isRequesting
                ),
            infoButton =
                if (isSwap) {
                    IconButtonState(
                        icon = R.drawable.ic_help,
                        contentDescription = stringRes(R.string.unified_send_crosspay_info),
                        onClick = onCrossPayInfoClick,
                        isEnabled = !isRequesting
                    )
                } else {
                    null
                },
            isABHintVisible = state.isABHintVisible,
            zecAmount =
                NumberTextFieldState(
                    innerState = state.amounts.zec,
                    onValueChange = onZecAmountChange,
                    isEnabled = !isRequesting,
                    explicitError = if (state.showAmountError) stringRes("") else null
                ),
            fiatAmount =
                NumberTextFieldState(
                    innerState = state.amounts.fiat,
                    onValueChange = onFiatAmountChange,
                    isEnabled = !isRequesting && state.fiatPrice != null,
                    explicitError = if (state.showAmountError) stringRes("") else null
                ),
            fiatCurrency = state.fiatCurrency,
            isAmountSwapped = state.isAmountSwapped,
            onAmountSwap = onAmountSwap,
            amountError =
                if (state.showAmountError) {
                    stringRes(R.string.send_amount_insufficient_balance)
                } else {
                    null
                },
            theyReceive =
                createTheyReceiveState(
                    state = state,
                    onTokenAmountChange = onTokenAmountChange,
                    onTokenFiatAmountChange = onTokenFiatAmountChange,
                    onDestinationCurrencySwap = onDestinationCurrencySwap
                ),
            payEstimate = if (state.isExactOutput) createPayEstimate(state, onPayEstimateClick) else null,
            slippage = createSlippageButton(state, onSlippageClick),
            memo = createMemoState(state, onMemoChange),
            amountErrorFooter = null,
            errorFooter = createErrorFooter(state),
            infoFooter =
                if (!isSwap && state.needsTopUp) {
                    stringRes(R.string.top_up_balance_subtitle)
                } else {
                    null
                },
            onBack = onBack,
            primaryButton =
                createPrimaryButton(
                    state = state,
                    onPrimaryButtonClick = onPrimaryButtonClick,
                    onTryAgainClick = onTryAgainClick,
                    onTopUpClick = onTopUpClick
                ),
        )
    }

    private fun createAssetState(
        state: UnifiedSendInternalState,
        onAssetPickerClick: () -> Unit
    ): AssetCardState {
        val asset = state.asset
        return if (asset == null) {
            AssetCardState.Loading(onClick = onAssetPickerClick, isEnabled = !state.isRequestingQuote)
        } else {
            AssetCardState.Data(
                token = stringRes(asset.tokenTicker),
                chain = if (asset is ZecSwapAsset) null else asset.chainName,
                isSingleLine = true,
                bigIcon = asset.tokenIcon,
                smallIcon = if (asset is ZecSwapAsset) null else asset.chainIcon,
                onClick = onAssetPickerClick,
                isEnabled = !state.isRequestingQuote,
            )
        }
    }

    private fun createAddressState(
        state: UnifiedSendInternalState,
        onAddressChange: (String) -> Unit
    ) = TextFieldState(
        value = stringRes(if (state.isSwap) state.swapAddress else state.zcashAddress),
        error =
            if (!state.isSwap &&
                state.zcashAddress.isNotEmpty() &&
                state.zcashAddressType is AddressType.Invalid
            ) {
                stringRes(R.string.send_address_invalid)
            } else {
                null
            },
        onValueChange = onAddressChange,
        isEnabled = !state.isRequestingQuote,
    )

    private fun createAddressPlaceholder(state: UnifiedSendInternalState) =
        if (state.isSwap && state.asset != null) {
            stringRes(
                co.electriccoin.zcash.ui.design.R.string.general_enter_address_partial,
                state.asset.chainName
            )
        } else {
            stringRes(R.string.unified_send_address_placeholder)
        }

    private fun createContactChip(
        state: UnifiedSendInternalState,
        onDeleteSwapContactClick: () -> Unit
    ): ChipButtonState? {
        val contact = state.contact ?: return null
        return ChipButtonState(
            text = stringRes(contact.contact.name),
            onClick = onDeleteSwapContactClick,
            endIcon = co.electriccoin.zcash.ui.design.R.drawable.ic_chip_close,
            isEnabled = !state.isRequestingQuote,
        )
    }

    /**
     * The destination row: one field, showing either the token or its USD value — never both, so the
     * form carries a single figure per side. In exact-output it holds what the user typed; in
     * exact-input it carries the estimate, pre-selected so typing replaces a figure they never
     * entered rather than appending to it.
     */
    private fun createTheyReceiveState(
        state: UnifiedSendInternalState,
        onTokenAmountChange: (NumberTextFieldInnerState) -> Unit,
        onTokenFiatAmountChange: (NumberTextFieldInnerState) -> Unit,
        onDestinationCurrencySwap: () -> Unit,
    ): TheyReceiveState? {
        val asset = state.asset
        if (!state.isSwap || asset == null) return null
        // With no price there is nothing to convert with, so the USD side is not offered at all.
        val canShowFiat = asset.usdPrice != null
        val isFiat = canShowFiat && state.destinationCurrency == CurrencyType.FIAT
        return TheyReceiveState(
            label =
                stringRes(
                    if (state.isExactOutput) {
                        R.string.unified_send_they_receive_exact
                    } else {
                        R.string.unified_send_they_receive_approx
                    }
                ),
            unit = if (isFiat) FiatCurrency.USD.code else asset.tokenTicker,
            amount =
                createDestinationField(
                    state = state,
                    asset = asset,
                    isFiat = isFiat,
                    onChange = if (isFiat) onTokenFiatAmountChange else onTokenAmountChange
                ),
            onSwapCurrency = onDestinationCurrencySwap.takeIf { canShowFiat && !state.isRequestingQuote },
        )
    }

    private fun createDestinationField(
        state: UnifiedSendInternalState,
        asset: SwapAsset,
        isFiat: Boolean,
        onChange: (NumberTextFieldInnerState) -> Unit,
    ): NumberTextFieldState =
        if (state.isExactOutput) {
            NumberTextFieldState(
                innerState = if (isFiat) state.amounts.tokenFiat else state.amounts.token,
                onValueChange = onChange,
                isEnabled = !state.isRequestingQuote,
                explicitError = if (state.showAmountError) stringRes("") else null
            )
        } else {
            val estimate = estimateDestinationAmount(state, asset)
            val shown = if (isFiat) estimateUsdFromToken(estimate, asset.usdPrice) else estimate
            NumberTextFieldState(
                // Pre-selected, so typing replaces a figure the user never entered.
                innerState = shown.toAmountState(SELECT_ALL),
                onValueChange = { inner -> if (isEntry(inner, shown)) onChange(inner) },
                isEnabled = !state.isRequestingQuote,
            )
        }

    /**
     * Whether a field callback carries an actual entry rather than a bare caret move. The text field
     * reports a selection change as a value change, and the estimate is handed over pre-selected, so
     * without this a tap on a figure the user never typed would pin the payment to the recipient's
     * side. Deleting the estimate does count — that is someone clearing the field to type their own.
     */
    private fun isEntry(inner: NumberTextFieldInnerState, shown: BigDecimal?): Boolean {
        val typed = inner.amount ?: return shown != null
        return shown == null || typed.compareTo(shown) != 0
    }

    /**
     * What the typed ZEC buys, rounded to what the field will actually render. `stripFractionsDynamically`
     * pads back out to 8 decimals, so the closing truncate returns the stored amount to the asset's own
     * precision and it matches the figure on screen exactly.
     */
    private fun estimateDestinationAmount(state: UnifiedSendInternalState, asset: SwapAsset): BigDecimal? =
        estimateTokenFromZec(state.amounts.zec.amount, state.zecUsdPrice, asset.usdPrice)
            ?.truncateToAssetDecimals(asset.decimals)
            ?.stripFractionsDynamically()
            ?.truncateToAssetDecimals(asset.decimals)

    /** The greyed "≈ 0.42 ZEC" that stands in for the pay fields while exact-output is in force. */
    private fun createPayEstimate(
        state: UnifiedSendInternalState,
        onPayEstimateClick: () -> Unit
    ) = PayEstimateState(
        text =
            stringRes(
                R.string.unified_send_estimated_equivalent,
                // Prices have not loaded yet. The payment still works — NEAR prices it — so say the
                // cost is unknown rather than dropping the "I'll pay" row entirely.
                state.zecValue?.let { stringResByDynamicNumber(it) } ?: stringRes("—"),
                CURRENCY_TICKER
            ),
        onClick = onPayEstimateClick.takeIf { !state.isRequestingQuote }
    )

    private fun createSlippageButton(
        state: UnifiedSendInternalState,
        onSlippageClick: (BigDecimal?, SwapMode) -> Unit
    ): ButtonState? {
        if (!state.isSwap) return null
        return ButtonState(
            text = stringResByNumber(state.slippage, minDecimals = 0) + stringRes("%"),
            icon = R.drawable.ic_swap_slippage,
            // The repository reads the tolerance again when the request is built, so a change made
            // mid-flight would silently disagree with the quote the user is about to be shown.
            isEnabled = !state.isRequestingQuote,
            onClick = { onSlippageClick(state.originUsd, state.mode) },
        )
    }

    private fun createMemoState(
        state: UnifiedSendInternalState,
        onMemoChange: (String) -> Unit
    ): MemoFieldState? =
        if (state.isSwap) {
            null
        } else {
            MemoFieldState.Editable(
                text = state.memo,
                byteCount = state.memoByteCount,
                maxBytes = MAX_MEMO_BYTES,
                isEnabled = state.zcashAddressType != AddressType.Transparent && !state.isRequestingQuote,
                onValueChange = onMemoChange
            )
        }

    private fun createErrorFooter(state: UnifiedSendInternalState): SwapErrorFooterState? {
        val error = state.swapAssets.error ?: return null
        val isUnavailable =
            error is ResponseException &&
                error.response.status
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
    private fun createPrimaryButton(
        state: UnifiedSendInternalState,
        onPrimaryButtonClick: (Boolean) -> Unit,
        onTryAgainClick: () -> Unit,
        onTopUpClick: () -> Unit,
    ): PrimaryButtonState {
        val swapAssets = state.swapAssets
        val error = swapAssets.error
        val isFirstLoad = swapAssets.isLoading && swapAssets.data == null
        val isUnavailable =
            error is ResponseException &&
                error.response.status
                    .isServiceUnavailable()

        return when {
            // Service unavailable blocks the button outright — retrying will not help.
            state.isSwap && isUnavailable -> {
                PrimaryButtonState.Disabled
            }

            // Nothing to spend at all, before any amount is entered.
            !state.isSwap && state.hasZeroBalance -> {
                PrimaryButtonState.TopUp(onClick = onTopUpClick)
            }

            // Not enough for what was entered.
            state.isAmountValid && !state.hasFunds -> {
                PrimaryButtonState.TopUp(onClick = onTopUpClick)
            }

            !state.isSwap -> {
                if (state.canSubmitZecSend) {
                    PrimaryButtonState.Review(
                        isLoading = state.isRequestingQuote,
                        onClick = { onPrimaryButtonClick(false) }
                    )
                } else {
                    PrimaryButtonState.Disabled
                }
            }

            error != null -> {
                PrimaryButtonState.Retry(isLoading = isFirstLoad, onClick = onTryAgainClick)
            }

            isFirstLoad -> {
                PrimaryButtonState.Loading
            }

            swapAssets.data != null && state.isAddressValid && state.isAmountValid -> {
                PrimaryButtonState.Review(
                    isLoading = state.isRequestingQuote,
                    onClick = { onPrimaryButtonClick(true) }
                )
            }

            else -> {
                PrimaryButtonState.Disabled
            }
        }
    }
}
