// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.refunds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRecords
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.ObserveLocalCurrencyUseCase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.common.privateusd.Sepolia
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.authenticateSpend
import co.electriccoin.zcash.ui.screen.privateusd.dateTime
import co.electriccoin.zcash.ui.screen.privateusd.runConversionStep
import co.electriccoin.zcash.ui.screen.privateusd.toFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ReverseSwapRecord
import xyz.justzappit.railgun.RailgunNetwork
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PrivateUsdRefundsVM(
    records: ReverseSwapRecords,
    private val repository: ReverseSwapRepository,
    private val auth: SecretAuthGate,
    observeLocalCurrency: ObserveLocalCurrencyUseCase,
    private val navigation: NavigationRouter,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val form = MutableStateFlow(RefundActions())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val checks =
        combine(
            records.observeHistory
                .map { history ->
                    history.filter { it.phase == ReversePhase.REFUNDED }.reversed()
                }.distinctUntilChanged(),
            refresh,
        ) { records, _ -> records }
            .flatMapLatest { records ->
                channelFlow {
                    var checked = records.map { RefundCheck(it, null, null) }
                    val updates = Mutex()
                    val concurrent = Semaphore(CHECK_CONCURRENCY)
                    send(RefundChecks(checked, isRefreshing = records.isNotEmpty()))
                    while (records.isNotEmpty()) {
                        coroutineScope {
                            records.forEach { record ->
                                launch {
                                    concurrent.withPermit {
                                        val result =
                                            withTimeoutOrNull(CHECK_TIMEOUT) {
                                                runConversionStep("no refund recovery check") {
                                                    repository.canRescue(record.index)
                                                }
                                            } ?: Result.failure(IllegalStateException("refund check timed out"))
                                        val check =
                                            RefundCheck(
                                                record,
                                                result.getOrNull(),
                                                result
                                                    .exceptionOrNull()
                                                    ?.toFailure()
                                                    ?.message(R.string.refunds_check_failed)
                                            )
                                        updates.withLock {
                                            checked = checked.map { if (it.record.index == record.index) check else it }
                                            send(
                                                RefundChecks(
                                                    checked,
                                                    isRefreshing =
                                                        checked.any {
                                                            it.recoverable == null &&
                                                                it.error == null
                                                        }
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        delay(CHECK_AFTER)
                    }
                }
            }.retryWhen { error, _ ->
                emit(RefundChecks(error = error.toFailure().message(R.string.convert_error_store)))
                delay(CHECK_AFTER)
                true
            }

    internal val state =
        combine(checks, form, observeLocalCurrency(), auth.pinPrompt) { checks, actions, currency, pin ->
            PrivateUsdRefundsState(
                refunds = checks.refunds.map { it.toState(actions, currency) },
                isLoading = false,
                isRefreshing = checks.isRefreshing,
                error = checks.error,
                isBackEnabled = actions.busy == null,
                pinVerify = pin,
                onRefresh = ::onRefresh,
                onBack = ::onBack,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            PrivateUsdRefundsState(onRefresh = ::onRefresh, onBack = ::onBack),
        )

    private fun RefundCheck.toState(actions: RefundActions, currency: LocalCurrency): PrivateUsdRefundState {
        val network =
            if (record.deployment.chainId == Sepolia.CHAIN_ID) RailgunNetwork.SEPOLIA else RailgunNetwork.MAINNET
        val token = PrivateUsdTokens.find(network, record.quote.terms.token)
        val busy = actions.busy == record.index
        return PrivateUsdRefundState(
            index = record.index,
            date = dateTime(Instant.fromEpochSeconds(record.acceptedAt ?: record.quote.terms.expiresAt)),
            amount =
                token?.let {
                    currency
                        .format(
                            record.quote.terms.amount.micros
                                .toDecimal(it.decimals)
                        ).asPrivacySensitive()
                },
            status =
                stringRes(
                    when {
                        busy -> R.string.refunds_recovering
                        error != null -> R.string.refunds_check_failed
                        recoverable == null -> R.string.refunds_checking
                        recoverable -> R.string.refunds_available
                        else -> R.string.refunds_no_recovery
                    },
                ),
            isProblem = error != null,
            recover =
                if (recoverable == true || busy) {
                    ButtonState(
                        stringRes(R.string.refunds_recover),
                        isEnabled = actions.busy == null,
                        isLoading = busy,
                    ) {
                        recover(record.index)
                    }
                } else {
                    null
                },
            error = actions.errors[record.index] ?: error,
        )
    }

    private fun recover(index: Int) {
        if (form.value.busy != null) return
        if (state.value.refunds.none { it.index == index && it.recover?.isEnabled == true }) return
        form.update { it.copy(busy = index, errors = it.errors - index) }
        viewModelScope.launch {
            try {
                runConversionStep("a refund wasn't recovered") {
                    if (auth.authenticateSpend()) repository.rescue(index)
                }.exceptionOrNull()?.let { error ->
                    val message = error.toFailure().message(R.string.refunds_recover_failed)
                    form.update { it.copy(errors = it.errors + (index to message)) }
                }
            } finally {
                form.update { it.copy(busy = null) }
                refresh.update { it + 1 }
            }
        }
    }

    private fun onRefresh() {
        if (form.value.busy == null) refresh.update { it + 1 }
    }

    private fun onBack() {
        if (form.value.busy == null) navigation.back()
    }

    private data class RefundActions(
        val busy: Int? = null,
        val errors: Map<Int, StringResource> = emptyMap()
    )

    private data class RefundCheck(
        val record: ReverseSwapRecord,
        val recoverable: Boolean?,
        val error: StringResource?
    )

    private data class RefundChecks(
        val refunds: List<RefundCheck> = emptyList(),
        val isRefreshing: Boolean = false,
        val error: StringResource? = null,
    )

    private companion object {
        val CHECK_AFTER = 15.seconds
        val CHECK_TIMEOUT = 30.seconds
        const val CHECK_CONCURRENCY = 4
    }
}
