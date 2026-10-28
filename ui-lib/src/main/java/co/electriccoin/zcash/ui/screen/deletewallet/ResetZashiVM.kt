package co.electriccoin.zcash.ui.screen.deletewallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.component.destructive
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.usecase.EnsureNoUnfinishedConversionsUseCase
import co.electriccoin.zcash.ui.common.usecase.EnsureNoUnsharedGiftFundsUseCase
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.gift.GiftCardListArgs
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class ResetZashiVM(
    private val navigationRouter: NavigationRouter,
    private val resetZashi: ResetZashiUseCase,
    private val errorStateMapper: ErrorMapperUseCase,
    private val ensureNoUnsharedGiftFunds: EnsureNoUnsharedGiftFundsUseCase,
    private val ensureNoUnfinishedConversions: EnsureNoUnfinishedConversionsUseCase,
) : ViewModel() {
    private val isKeepFilesChecked = MutableStateFlow(true)
    private val confirmationDialogFlow = MutableStateFlow<ZashiConfirmationState?>(null)
    private val resetLce = mutableLce<Unit>()

    /** "Delete anyway" on the gifts dialog, remembered for the conversions dialog that may follow it. */
    private var isGiftLossAccepted = false

    val state: StateFlow<LceState<ResetZashiState>> =
        combine(
            isKeepFilesChecked,
            confirmationDialogFlow,
            resetLce.state,
        ) { isKeepFilesChecked, confirmationDialog, lce ->
            createState(
                isKeepFilesChecked = isKeepFilesChecked,
                confirmationDialog = confirmationDialog,
                isLoading = lce.loading,
            )
        }.withLce(resetLce, errorStateMapper::mapToState)
            .stateIn(this, LceState(content = createState(isKeepFilesChecked.value, null, false)))

    private fun createState(
        isKeepFilesChecked: Boolean,
        confirmationDialog: ZashiConfirmationState?,
        isLoading: Boolean,
    ) = ResetZashiState(
        onBack = { navigationRouter.back() },
        checkboxState =
            CheckboxState(
                title = stringRes(R.string.delete_wallet_checkbox_title),
                subtitle = stringRes(R.string.delete_wallet_checkbox_warning_checked),
                isChecked = isKeepFilesChecked,
                onClick = ::onCheckboxToggled
            ),
        buttonState =
            ButtonState(
                text = stringRes(R.string.delete_wallet_button),
                isLoading = isLoading,
                onClick = ::onConfirmClicked
            ),
        confirmationDialog = confirmationDialog,
    )

    private fun onCheckboxToggled() = isKeepFilesChecked.update { !it }

    // ResetZashiUseCase refuses both cases anyway; the dialogs are what turn its refusals into a way out.
    private fun onConfirmClicked() {
        isGiftLossAccepted = false
        viewModelScope.launch {
            confirmationDialogFlow.value =
                when {
                    hasUnsharedGiftFunds() -> createGiftFundsWarningState()
                    hasUnfinishedConversions() -> createConversionsWarningState()
                    else -> createConfirmationState()
                }
        }
    }

    private suspend fun hasUnsharedGiftFunds() = runCatching { ensureNoUnsharedGiftFunds() }.isFailure

    private suspend fun hasUnfinishedConversions() = runCatching { ensureNoUnfinishedConversions() }.isFailure

    private fun createGiftFundsWarningState(): ZashiConfirmationState =
        ZashiConfirmationState(
            icon = R.drawable.ic_reset_zashi_warning,
            title = stringRes(R.string.delete_wallet_gift_cards_title),
            message = stringRes(R.string.delete_wallet_gift_cards_message),
            primaryAction =
                ButtonState(
                    text = stringRes(R.string.delete_wallet_gift_cards_review),
                    style = ButtonStyle.PRIMARY,
                    onClick = ::onReviewGiftCards,
                ),
            secondaryAction =
                ButtonState(
                    text = stringRes(R.string.delete_wallet_gift_cards_delete_anyway),
                    style = ButtonStyle.TERTIARY,
                    onClick = ::onConfirmDiscardGifts,
                ),
            onBack = ::onDismissConfirmation,
        )

    private fun onReviewGiftCards() {
        confirmationDialogFlow.value = null
        navigationRouter.forward(GiftCardListArgs)
    }

    private fun createConversionsWarningState(): ZashiConfirmationState =
        ZashiConfirmationState(
            icon = R.drawable.ic_reset_zashi_warning,
            title = stringRes(R.string.delete_wallet_conversions_title),
            message = stringRes(R.string.delete_wallet_conversions_message),
            primaryAction =
                ButtonState(
                    text = stringRes(R.string.delete_wallet_conversions_review),
                    style = ButtonStyle.PRIMARY,
                    onClick = ::onReviewConversions,
                ),
            secondaryAction =
                ButtonState(
                    text = stringRes(R.string.delete_wallet_conversions_delete_anyway),
                    style = ButtonStyle.TERTIARY,
                    onClick = ::onConfirmDiscardConversions,
                ),
            onBack = ::onDismissConfirmation,
        )

    private fun onReviewConversions() {
        confirmationDialogFlow.value = null
        navigationRouter.forward(PrivateUsdArgs)
    }

    private fun createConfirmationState(): ZashiConfirmationState =
        ZashiConfirmationState.destructive(
            title = stringRes(R.string.delete_wallet_confirmation_title),
            message = stringRes(R.string.delete_wallet_confirmation_subtitle),
            primaryText = stringRes(R.string.delete_wallet_confirmation_button),
            secondaryText = stringRes(R.string.delete_wallet_confirmation_cancel),
            onPrimary = ::onConfirmReset,
            onBack = ::onDismissConfirmation,
        )

    private fun onDismissConfirmation() {
        confirmationDialogFlow.value = null
    }

    private fun onConfirmReset() {
        confirmationDialogFlow.value = null
        reset(allowConversionLoss = false)
    }

    // Discarding the gifts was the last word before; now a conversion under way still gets its own dialog first.
    private fun onConfirmDiscardGifts() {
        confirmationDialogFlow.value = null
        isGiftLossAccepted = true
        viewModelScope.launch {
            if (hasUnfinishedConversions()) {
                confirmationDialogFlow.value = createConversionsWarningState()
            } else {
                reset(allowConversionLoss = false)
            }
        }
    }

    private fun onConfirmDiscardConversions() {
        confirmationDialogFlow.value = null
        reset(allowConversionLoss = true)
    }

    // A cancelled prompt ends the loading state and nothing else, as before; the result is not shown.
    private fun reset(allowConversionLoss: Boolean) {
        resetLce.execute {
            resetZashi(
                keepFiles = isKeepFilesChecked.value,
                allowGiftDataLoss = isGiftLossAccepted,
                allowConversionLoss = allowConversionLoss,
            )
        }
    }
}
