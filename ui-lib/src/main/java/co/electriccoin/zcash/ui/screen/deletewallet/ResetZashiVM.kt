package co.electriccoin.zcash.ui.screen.deletewallet

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.component.destructive
import co.electriccoin.zcash.ui.common.component.error
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.usecase.EnsureNoUnfinishedConversionsUseCase
import co.electriccoin.zcash.ui.common.usecase.EnsureNoUnsharedGiftFundsUseCase
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.UnfinishedConversionsException
import co.electriccoin.zcash.ui.common.usecase.UnsharedGiftFundsException
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.gift.GiftCardListArgs
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdArgs
import co.electriccoin.zcash.ui.screen.privateusd.refunds.PrivateUsdRefundsArgs
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
    private val isChecking = MutableStateFlow(false)
    private val resetLce = mutableLce<Unit>()

    /** "Delete anyway" on the gifts dialog, remembered for the dialogs that may follow it. */
    private var isGiftLossAccepted = false

    /** "Delete anyway" on the conversions dialog, remembered for the last confirmation. */
    private var isConversionLossAccepted = false

    val state: StateFlow<LceState<ResetZashiState>> =
        combine(
            isKeepFilesChecked,
            confirmationDialogFlow,
            resetLce.state,
            isChecking,
            resetZashi.pinPrompt,
        ) { isKeepFilesChecked, confirmationDialog, lce, isChecking, pinPrompt ->
            createState(
                isKeepFilesChecked = isKeepFilesChecked,
                confirmationDialog = confirmationDialog,
                isLoading = lce.loading || isChecking,
                pinVerify = pinPrompt,
            )
        }.withLce(resetLce, errorStateMapper::mapToState)
            .stateIn(this, LceState(content = createState(isKeepFilesChecked.value, null, false)))

    private fun createState(
        isKeepFilesChecked: Boolean,
        confirmationDialog: ZashiConfirmationState?,
        isLoading: Boolean,
        pinVerify: PinVerifyState? = null,
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
        pinVerify = pinVerify,
    )

    private fun onCheckboxToggled() = isKeepFilesChecked.update { !it }

    // ResetZashiUseCase refuses both cases anyway; the dialogs are what turn its refusals into a way out.
    private fun onConfirmClicked() {
        isGiftLossAccepted = false
        isConversionLossAccepted = false
        showAfterChecking {
            if (hasUnsharedGiftFunds()) createGiftFundsWarningState() else conversionsOrConfirmation()
        }
    }

    // A refund's check can reach the network, so the button shows it's working.
    private fun showAfterChecking(dialog: suspend () -> ZashiConfirmationState) {
        viewModelScope.launch {
            isChecking.value = true
            try {
                confirmationDialogFlow.value = dialog()
            } finally {
                isChecking.value = false
            }
        }
    }

    private suspend fun hasUnsharedGiftFunds() = runCatching { ensureNoUnsharedGiftFunds() }.isFailure

    private suspend fun conversionsOrConfirmation(): ZashiConfirmationState =
        try {
            ensureNoUnfinishedConversions()
            createConfirmationState()
        } catch (blocked: UnfinishedConversionsException) {
            createConversionsWarningState(blocked)
        }

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

    private fun createConversionsWarningState(blocked: UnfinishedConversionsException): ZashiConfirmationState =
        when {
            blocked.count == null -> {
                conversionsWarning(
                    title = R.string.delete_wallet_conversions_unreadable_title,
                    message = R.string.delete_wallet_conversions_unreadable_message,
                    primary = R.string.delete_wallet_try_again,
                    onPrimary = ::onRetryConversionsCheck,
                )
            }

            blocked.hasRefund -> {
                conversionsWarning(
                    title = R.string.delete_wallet_refund_title,
                    message = R.string.delete_wallet_refund_message,
                    primary = R.string.delete_wallet_refund_review,
                    onPrimary = ::onReviewRefunds,
                )
            }

            else -> {
                conversionsWarning(
                    title = R.string.delete_wallet_conversions_title,
                    message = R.string.delete_wallet_conversions_message,
                    primary = R.string.delete_wallet_conversions_review,
                    onPrimary = ::onReviewConversions,
                )
            }
        }

    private fun conversionsWarning(
        @StringRes title: Int,
        @StringRes message: Int,
        @StringRes primary: Int,
        onPrimary: () -> Unit,
    ) = ZashiConfirmationState(
        icon = R.drawable.ic_reset_zashi_warning,
        title = stringRes(title),
        message = stringRes(message),
        primaryAction =
            ButtonState(
                text = stringRes(primary),
                style = ButtonStyle.PRIMARY,
                onClick = onPrimary,
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

    private fun onReviewRefunds() {
        confirmationDialogFlow.value = null
        navigationRouter.forward(PrivateUsdRefundsArgs)
    }

    private fun onRetryConversionsCheck() {
        confirmationDialogFlow.value = null
        showAfterChecking { conversionsOrConfirmation() }
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

    private fun createFailedState(): ZashiConfirmationState =
        ZashiConfirmationState.error(
            message = stringRes(R.string.delete_wallet_failed),
            primaryText = stringRes(R.string.delete_wallet_try_again),
            onPrimary = ::onConfirmReset,
            onBack = ::onDismissConfirmation,
        )

    private fun onDismissConfirmation() {
        confirmationDialogFlow.value = null
    }

    private fun onConfirmReset() {
        confirmationDialogFlow.value = null
        reset()
    }

    // Every way here ends on the last confirmation, or on a conversion's own dialog first.
    private fun onConfirmDiscardGifts() {
        confirmationDialogFlow.value = null
        isGiftLossAccepted = true
        showAfterChecking { conversionsOrConfirmation() }
    }

    private fun onConfirmDiscardConversions() {
        isConversionLossAccepted = true
        confirmationDialogFlow.value = createConfirmationState()
    }

    // A cancelled prompt ends the loading state and nothing else, as before. A refusal here means something started
    // behind the prompt, and gets the dialog it would have got before it.
    private fun reset() {
        resetLce.execute {
            try {
                val result =
                    resetZashi(
                        keepFiles = isKeepFilesChecked.value,
                        allowGiftDataLoss = isGiftLossAccepted,
                        allowConversionLoss = isConversionLossAccepted,
                    )
                if (result == ResetZashiResult.SEED_NOT_CLEARED) confirmationDialogFlow.value = createFailedState()
            } catch (_: UnsharedGiftFundsException) {
                confirmationDialogFlow.value = createGiftFundsWarningState()
            } catch (blocked: UnfinishedConversionsException) {
                confirmationDialogFlow.value = createConversionsWarningState(blocked)
            }
        }
    }
}
