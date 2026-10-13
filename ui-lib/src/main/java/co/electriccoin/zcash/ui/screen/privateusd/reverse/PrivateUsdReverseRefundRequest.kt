// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.reverse

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.ReverseSwapRepository
import co.electriccoin.zcash.ui.common.security.SecretAuthGate
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.authenticateSpend
import co.electriccoin.zcash.ui.screen.privateusd.runConversionStep
import co.electriccoin.zcash.ui.screen.privateusd.toFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.atomicswap.ReverseSwapStatus

/** Requests a refund independently of an authorized funding proof that may still be running. */
internal class PrivateUsdReverseRefundRequest(
    private val form: MutableStateFlow<ReverseForm>,
    private val repository: ReverseSwapRepository,
    private val secretAuthGate: SecretAuthGate,
    private val scope: CoroutineScope,
    private val fundingJob: () -> Job?,
) {
    fun request(index: Int) {
        val record = repository.state.value.record ?: return
        val current = form.value
        if (record.index != index || !current.canRequestRefund) return
        if (record.isSettled) {
            form.update { it.copy(error = stringRes(R.string.reverse_refund_unavailable)) }
        } else if ((record.status as? ReverseSwapStatus.UnderWay)?.cancellable == true) {
            requestRefund(index, fundingJob().takeIf { current.canCancelFunding })
        }
    }

    private fun requestRefund(index: Int, fundingJob: Job?) {
        form.update { it.copy(isCancelling = true, error = null) }
        scope.launch {
            var error: StringResource? = null
            var requested = false
            try {
                error =
                    runConversionStep("a reverse refund wasn't requested") {
                        if (secretAuthGate.authenticateSpend()) {
                            try {
                                repository.cancel(index)
                                requested = true
                            } finally {
                                fundingJob?.cancelAndJoin()
                            }
                        }
                    }.exceptionOrNull()?.toFailure()?.message(R.string.convert_call_off_failed)
            } finally {
                val record =
                    repository.state.value.record
                        ?.takeIf { it.index == index }
                val recorded = record?.let { it.cancelRequested && !it.isSettled } == true
                val acknowledged = requested || recorded
                form.update {
                    it.copy(
                        isCancelling = false,
                        error = error.takeUnless { recorded } ?: it.error.takeUnless { acknowledged },
                    )
                }
            }
        }
    }
}
