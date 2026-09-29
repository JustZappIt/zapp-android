// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.DollarRate
import co.electriccoin.zcash.ui.common.privateusd.ObserveDollarRateUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdAsset
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdBalanceState
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendLog
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendMode
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendRecord
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendRequest
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSenders
import co.electriccoin.zcash.ui.common.privateusd.local
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.common.privateusd.tokenAmount
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.authorizeSpend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.railgun.RailgunException
import java.io.IOException
import kotlin.time.Clock

class PrivateUsdSendVM(
    args: PrivateUsdSendArgs,
    private val balanceRepository: PrivateUsdBalanceRepository,
    senders: PrivateUsdSenders,
    railgunWalletRepository: RailgunWalletRepository,
    atomicSwapRepository: AtomicSwapRepository,
    observeDollarRate: ObserveDollarRateUseCase,
    private val biometricRepository: BiometricRepository,
    private val sendLog: PrivateUsdSendLog,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val sender = checkNotNull(senders.current) { "no sending in this build" }
    private val explorerTxUrl = atomicSwapRepository.deployment?.explorerTxUrl
    private val form =
        MutableStateFlow(
            PrivateUsdSendForm(mode = if (args.withdraw) PrivateUsdSendMode.WITHDRAW else PrivateUsdSendMode.PRIVATE)
        )

    internal val state: StateFlow<PrivateUsdSendState> =
        combine(
            form,
            balanceRepository.observe(),
            railgunWalletRepository.state.map { it.proof?.progress },
            observeDollarRate(),
            ::createState,
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(form.value, balanceRepository.state.value, null, null),
        )

    private fun createState(
        form: PrivateUsdSendForm,
        balance: PrivateUsdBalanceState,
        proof: Float?,
        rate: DollarRate?,
    ): PrivateUsdSendState {
        val assets =
            balance.balances
                ?.assets
                ?.filter { it.token.isDollar && it.available.signum() > 0 }
                .orEmpty()
        val token = form.reviewedRequest?.token?.address ?: form.token
        val asset =
            assets.firstOrNull { it.token.address == token }
                ?: assets.firstOrNull().takeIf { form.phase == PrivateUsdSendPhase.FORM }
        val amountError = form.amountError(asset)
        val availableRequest = asset?.let(form::request)
        val request = form.reviewedRequest ?: availableRequest
        val canSend = form.canSend(request, balance)
        val isWithdrawal = form.mode == PrivateUsdSendMode.WITHDRAW
        return PrivateUsdSendState(
            phase = form.phase,
            isWithdrawal = isWithdrawal,
            onModeSelect = { index ->
                updateForm {
                    it.copy(mode = PrivateUsdSendMode.entries[index], recipient = "", cost = null, error = null)
                }
            },
            assets = assets.map { it.token.symbol },
            selectedAsset = assets.indexOf(asset).coerceAtLeast(0),
            onAssetSelect = { index -> assets.getOrNull(index)?.let(::onAssetSelect) },
            amount =
                NumberTextFieldState(innerState = form.amount, isEnabled = !form.isBusy) { inner ->
                    updateForm { it.copy(amount = inner, cost = null) }
                },
            amountNote =
                amountError
                    ?: form.amount.amount
                        ?.takeIf { rate != null }
                        ?.let { stringRes(R.string.private_usd_worth, rate.local(it)) },
            isAmountInvalid = amountError != null,
            available = asset?.let { tokenAmount(it.available, it.token) },
            onMax = { asset?.let(::onMax) },
            recipient = form.recipient,
            onRecipientChange = { recipient -> updateForm { it.copy(recipient = recipient, cost = null) } },
            recipientError = form.recipientError(),
            review = request?.let { form.review(it, sender.usesTestAccount) },
            proofProgress = proof?.takeIf { form.phase == PrivateUsdSendPhase.SENDING }?.let { it / PERCENT },
            done = form.done(explorerTxUrl),
            error = form.error,
            info = if (isWithdrawal) WITHDRAW_INFO else SEND_INFO,
            primaryButton =
                primaryButton(form, request, canSend).let {
                    it.copy(isEnabled = it.isEnabled && !form.isBusy, isLoading = it.isLoading || form.isBusy)
                },
            onBack = ::onBack,
            isBusy = form.isBusy,
        )
    }

    private fun primaryButton(
        form: PrivateUsdSendForm,
        request: PrivateUsdSendRequest?,
        canSend: Boolean,
    ): ButtonState =
        when (form.phase) {
            PrivateUsdSendPhase.FORM -> {
                ButtonState(stringRes(R.string.private_usd_send_review), isEnabled = request != null) {
                    request?.let(::onReview)
                }
            }

            PrivateUsdSendPhase.REVIEW -> {
                ButtonState(
                    text =
                        stringRes(
                            if (form.mode == PrivateUsdSendMode.WITHDRAW) {
                                R.string.private_usd_send_confirm_withdraw
                            } else {
                                R.string.private_usd_send_confirm_private
                            }
                        ),
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
        }

    private fun onAssetSelect(asset: PrivateUsdAsset) =
        updateForm { it.copy(token = asset.token.address, amount = NumberTextFieldInnerState(), cost = null) }

    private fun onMax(asset: PrivateUsdAsset) =
        updateForm {
            it.copy(
                token = asset.token.address,
                amount = NumberTextFieldInnerState.fromAmount(asset.available.toDecimal(asset.token.decimals)),
                cost = null,
            )
        }

    private fun onReview(request: PrivateUsdSendRequest) {
        val current = form.value
        if (current.isBusy || current.phase != PrivateUsdSendPhase.FORM ||
            !current.canSend(request, balanceRepository.state.value)
        ) {
            return
        }
        form.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val cost = sender.cost(request)
                form.update { it.copy(phase = PrivateUsdSendPhase.REVIEW, cost = cost, reviewedRequest = request) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RailgunException) {
                onFailure(e)
            } catch (e: IllegalStateException) {
                onFailure(e)
            } finally {
                form.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun onConfirm() {
        val current = form.value
        val request = current.reviewedRequest ?: return
        if (current.isBusy || current.phase != PrivateUsdSendPhase.REVIEW ||
            !current.canSend(request, balanceRepository.state.value)
        ) {
            return
        }
        form.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            try {
                if (!biometricRepository.authorizeSpend()) return@launch
                form.update { it.copy(phase = PrivateUsdSendPhase.SENDING) }
                val sent = sender.send(request)
                // Success belongs to the submitted request, even after a balance refresh or log failure.
                form.update { it.copy(phase = PrivateUsdSendPhase.DONE, sent = sent) }
                recordSend(request, sent.txHash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RailgunException) {
                onFailure(e)
            } catch (e: IllegalStateException) {
                onFailure(e)
            } finally {
                form.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun updateForm(update: (PrivateUsdSendForm) -> PrivateUsdSendForm) {
        form.update { if (it.phase == PrivateUsdSendPhase.FORM && !it.isBusy) update(it) else it }
    }

    private fun onFailure(error: Exception) {
        Twig.warn(error) { "Private USD: the send failed" }
        form.update {
            it.copy(
                phase = if (it.reviewedRequest == null) PrivateUsdSendPhase.FORM else PrivateUsdSendPhase.REVIEW,
                error = stringRes(R.string.private_usd_send_failed),
            )
        }
    }

    private suspend fun recordSend(request: PrivateUsdSendRequest, txHash: String) {
        val record =
            PrivateUsdSendRecord(
                txHash = txHash,
                withdraw = request.mode == PrivateUsdSendMode.WITHDRAW,
                token = request.token.address,
                amount = request.amount.toString(),
                to = request.to,
                sentAt = Clock.System.now().epochSeconds,
            )
        try {
            sendLog.add(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StoreCorruptedException) {
            Twig.warn(e) { "Private USD: sent, but the activity could not be saved" }
        } catch (e: IOException) {
            Twig.warn(e) { "Private USD: sent, but the activity could not be saved" }
        } catch (e: IllegalStateException) {
            Twig.warn(e) { "Private USD: sent, but the activity could not be saved" }
        }
    }

    private fun onBack() {
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
        const val PERCENT = 100f

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

        val WITHDRAW_INFO =
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
                        stringRes(R.string.private_usd_withdraw_info_note_fee),
                    ),
            )
    }
}
