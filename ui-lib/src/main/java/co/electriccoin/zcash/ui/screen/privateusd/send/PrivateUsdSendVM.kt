// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendOutcome
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendRequest
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSenders
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendGuard
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSpendStatus
import co.electriccoin.zcash.ui.common.privateusd.basisPoints
import co.electriccoin.zcash.ui.common.privateusd.exactTokenAmount
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.common.security.PinVerifyState
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.authenticateSpend
import co.electriccoin.zcash.ui.screen.privateusd.message
import co.electriccoin.zcash.ui.screen.privateusd.runConversionStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.atomicswap.RAILGUN_FEE
import xyz.justzappit.offramp.peer.Bps
import xyz.justzappit.railgun.RailgunFees

class PrivateUsdSendVM(
    args: PrivateUsdSendArgs,
    private val balanceRepository: PrivateUsdBalanceRepository,
    senders: PrivateUsdSenders,
    railgunWalletRepository: RailgunWalletRepository,
    atomicSwapRepository: AtomicSwapRepository,
    observeLocalCurrency: ObserveLocalCurrencyUseCase,
    private val secretAuthGate: SecretAuthGate,
    private val navigationRouter: NavigationRouter,
    private val spendGuard: PrivateUsdSpendGuard,
) : ViewModel() {
    private val sender = checkNotNull(senders.current) { "no sending in this build" }
    private val explorerTxUrl = atomicSwapRepository.deployment?.explorerTxUrl
    private val railscanTxUrl = atomicSwapRepository.deployment?.railscanTxUrl
    private val form = MutableStateFlow(PrivateUsdSendForm(mode = args.mode))
    private var reviewJob: Job? = null

    internal val state: StateFlow<PrivateUsdSendState> =
        combine(
            form,
            balanceRepository.observe().onEach(::pinToken),
            combine(railgunWalletRepository.state, spendGuard.state) { wallet, spending ->
                RailgunProgress(wallet.proof?.progress, wallet.fees, spending)
            }.distinctUntilChanged(),
            observeLocalCurrency(),
            secretAuthGate.pinPrompt,
            ::createState,
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                createState(
                    form.value,
                    balanceRepository.state.value,
                    RailgunProgress(null, railgunWalletRepository.state.value.fees, spendGuard.state.value),
                    LocalCurrency.DOLLAR,
                    null,
                ),
        )

    private fun createState(
        form: PrivateUsdSendForm,
        balance: PrivateUsdBalanceState,
        railgun: RailgunProgress,
        currency: LocalCurrency,
        pin: PinVerifyState?,
    ): PrivateUsdSendState {
        val token = form.reviewedRequest?.token ?: form.token
        val assets = form.assets(balance)
        val asset = assets.firstOrNull { it.token == token }
        val amountError = form.amountError(asset)
        val request = form.reviewedRequest ?: asset?.let(form::request)
        val canSend = form.canSend(request, balance) && railgun.spending.canSend
        return PrivateUsdSendState(
            phase = form.phase,
            mode = form.mode,
            onModeSelect = { mode -> updateForm { it.copy(mode = mode, recipient = "") } },
            assets =
                assets.takeIf { it.size > 1 }.orEmpty().map { choice ->
                    PrivateUsdSendAssetState(choice.token.symbol, isSelected = choice.token == token) {
                        updateForm { it.copy(token = choice.token, amount = NumberTextFieldInnerState()) }
                    }
                },
            amount =
                NumberTextFieldState(innerState = form.amount, isEnabled = !form.isBusy) { inner ->
                    updateForm { it.copy(amount = inner) }
                },
            currencySymbol = LocalCurrency.DOLLAR.symbol,
            amountNote =
                amountError
                    ?: form.amount.amount
                        ?.takeUnless { currency.isDollar }
                        ?.let { stringRes(R.string.private_usd_worth, currency.format(it)) },
            isAmountInvalid = amountError != null,
            available = asset?.let { exactTokenAmount(it.available, it.token).asPrivacySensitive() },
            onMax = {
                asset?.let { max ->
                    updateForm {
                        it.copy(
                            token = max.token,
                            amount = NumberTextFieldInnerState.fromAmount(max.available.toDecimal(max.token.decimals)),
                        )
                    }
                }
            },
            recipient =
                TextFieldState(
                    value = stringRes(form.recipient),
                    error = form.recipientError(),
                    isEnabled = !form.isBusy,
                ) { recipient -> updateForm { it.copy(recipient = recipient) } },
            review = request?.let(form::review),
            proofProgress = railgun.proof?.takeIf { form.phase == PrivateUsdSendPhase.SENDING },
            done = form.done(explorerTxUrl, railscanTxUrl) { navigationRouter.forward(ExternalUrl(it)) },
            error = sendError(form, balance, railgun.spending),
            info = info(form.mode, form.cost?.feeBasisPoints ?: railgun.fees?.unshieldBasisPoints),
            primaryButton = primaryButton(form, request, canSend, railgun.spending),
            onBack = ::onBack,
            isBusy = form.isBusy,
            isBackEnabled = form.phase == PrivateUsdSendPhase.FORM || !form.isBusy,
            pinVerify = pin,
        )
    }

    // The first dollar there is to send is the default, and once one is picked a refresh can't swap it for another.
    private fun pinToken(balance: PrivateUsdBalanceState) {
        form.update { if (it.token == null) it.copy(token = it.assets(balance).firstOrNull()?.token) else it }
    }

    private fun primaryButton(
        form: PrivateUsdSendForm,
        request: PrivateUsdSendRequest?,
        canSend: Boolean,
        spending: PrivateUsdSpendStatus,
    ): ButtonState =
        when (form.phase) {
            PrivateUsdSendPhase.FORM -> {
                ButtonState(stringRes(R.string.convert_review), isEnabled = request != null) {
                    request?.let(::onReview)
                }
            }

            PrivateUsdSendPhase.REVIEW -> {
                ButtonState(
                    text = stringRes(form.mode.confirm),
                    isEnabled = canSend && form.cost != null,
                    onClick = ::onConfirm,
                )
            }

            PrivateUsdSendPhase.SENDING -> {
                ButtonState(stringRes(R.string.private_usd_send_sending), isEnabled = false, isLoading = true)
            }

            PrivateUsdSendPhase.DONE -> {
                ButtonState(stringRes(R.string.convert_result_done), onClick = navigationRouter::back)
            }
        }.let {
            it.copy(
                isEnabled = it.isEnabled && !form.isBusy && (form.phase !in EDITABLE_PHASES || spending.canSend),
                isLoading = it.isLoading || form.isBusy,
            )
        }

    private fun sendError(
        form: PrivateUsdSendForm,
        balance: PrivateUsdBalanceState,
        spending: PrivateUsdSpendStatus,
    ): StringResource? =
        form.error ?: form.reviewError(balance)
            ?: spending.message().takeIf { form.phase in EDITABLE_PHASES && !form.isBusy }

    private fun onReview(request: PrivateUsdSendRequest) {
        if (!spendGuard.state.value.canSend) return
        val current = form.value
        if (current.isBusy || current.phase != PrivateUsdSendPhase.FORM ||
            !current.canSend(request, balanceRepository.state.value)
        ) {
            return
        }
        form.update { it.copy(isBusy = true, error = null) }
        reviewJob =
            viewModelScope.launch {
                try {
                    runConversionStep("no send cost") { sender.cost(request) }.fold(
                        onSuccess = { cost ->
                            form.update {
                                it.copy(phase = PrivateUsdSendPhase.REVIEW, cost = cost, reviewedRequest = request)
                            }
                        },
                        onFailure = { form.update { it.notSent() } },
                    )
                } finally {
                    form.update { it.copy(isBusy = false) }
                }
            }
    }

    private fun onConfirm() {
        val current = form.value
        val request = current.reviewedRequest ?: return
        val canConfirm = spendGuard.state.value.canSend && !current.isBusy
        if (!canConfirm || current.phase != PrivateUsdSendPhase.REVIEW ||
            !current.canSend(request, balanceRepository.state.value)
        ) {
            return
        }
        form.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            try {
                if (authorize()) {
                    form.update { it.copy(phase = PrivateUsdSendPhase.SENDING) }
                    val outcome =
                        runConversionStep(
                            "the send didn't start"
                        ) { sender.send(request) }.getOrDefault(PrivateUsdSendOutcome.NotSent)
                    // Anything signed may be out there: it never goes back to review, where it could be paid again.
                    form.update {
                        if (outcome == PrivateUsdSendOutcome.Busy) {
                            it.copy(
                                phase = PrivateUsdSendPhase.REVIEW,
                                error = stringRes(R.string.private_usd_payment_pending),
                            )
                        } else if (outcome == PrivateUsdSendOutcome.NotSent) {
                            it.notSent()
                        } else {
                            it.copy(phase = PrivateUsdSendPhase.DONE, outcome = outcome)
                        }
                    }
                }
            } finally {
                form.update { it.copy(isBusy = false) }
            }
        }
    }

    private suspend fun authorize(): Boolean =
        runConversionStep("the send wasn't authorized") { secretAuthGate.authenticateSpend() }
            .onFailure { form.update { it.notSent() } }
            .getOrDefault(false)

    // An edit makes any earlier cost and error stale.
    private fun updateForm(update: (PrivateUsdSendForm) -> PrivateUsdSendForm) {
        form.update {
            if (it.phase == PrivateUsdSendPhase.FORM && !it.isBusy) update(it).copy(cost = null, error = null) else it
        }
    }

    // Back cancels a cost still loading on the form; it waits for a send the user authorized.
    private fun onBack() {
        if (form.value.isBusy && form.value.phase == PrivateUsdSendPhase.FORM) {
            reviewJob?.cancel()
            form.update { it.copy(isBusy = false) }
            navigationRouter.back()
            return
        }
        if (form.value.isBusy) return
        when (form.value.phase) {
            PrivateUsdSendPhase.REVIEW -> {
                form.update {
                    it.copy(phase = PrivateUsdSendPhase.FORM, reviewedRequest = null, cost = null, error = null)
                }
            }

            PrivateUsdSendPhase.SENDING -> {
                Unit
            }

            PrivateUsdSendPhase.FORM, PrivateUsdSendPhase.DONE -> {
                navigationRouter.back()
            }
        }
    }

    private companion object {
        val EDITABLE_PHASES = setOf(PrivateUsdSendPhase.FORM, PrivateUsdSendPhase.REVIEW)
        val SEND_INFO =
            PrivateUsdInfo(
                title = stringRes(R.string.private_usd_send_info_title),
                steps =
                    listOf(
                        stringRes(R.string.private_usd_send_info_step_address),
                        stringRes(R.string.private_usd_send_info_step_proof),
                        stringRes(R.string.private_usd_send_info_step_private),
                    ),
            )

        fun info(
            mode: PrivateUsdSendMode,
            feeBasisPoints: Int?
        ) = when (mode) {
            PrivateUsdSendMode.PRIVATE -> SEND_INFO
            PrivateUsdSendMode.WITHDRAW -> withdrawInfo(feeBasisPoints)
        }

        // Railgun's fee as the engine last read it, the one its contracts charge until it has.
        fun withdrawInfo(feeBasisPoints: Int?) =
            PrivateUsdInfo(
                title = stringRes(R.string.private_usd_withdraw_info_title),
                steps =
                    listOf(
                        stringRes(R.string.private_usd_withdraw_info_step_address),
                        stringRes(R.string.private_usd_withdraw_info_step_proof),
                    ),
                notes =
                    listOf(
                        stringRes(R.string.private_usd_withdraw_info_note_public),
                        stringRes(R.string.private_usd_withdraw_info_note_private),
                        stringRes(
                            R.string.private_usd_withdraw_info_note_fee,
                            basisPoints(feeBasisPoints?.let(::Bps) ?: RAILGUN_FEE),
                        ),
                    ),
            )
    }
}

private data class RailgunProgress(
    val proof: Float?,
    val fees: RailgunFees?,
    val spending: PrivateUsdSpendStatus,
)
