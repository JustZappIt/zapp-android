// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.OnrampCheckpointStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.onramp.FundsLocation
import xyz.justzappit.offramp.onramp.OnrampCheckpoint
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryDriver
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryPhase
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
import xyz.justzappit.offramp.onramp.fundsLocation
import xyz.justzappit.offramp.p2p.Usdc6

internal class OnrampZecDeliveryCoordinator(
    private val driver: OnrampZecDeliveryDriver,
    private val storage: OnrampCheckpointStorageProvider,
    private val scope: CoroutineScope,
    private val onStatus: (OnrampZecDeliveryStatus) -> Unit,
) {
    private var job: Job? = null
    private var startedOrderId: String? = null

    fun start(
        orderId: String,
        recipient: Address,
        amount: Usdc6,
        resume: OnrampZecDeliveryCheckpoint?,
        force: Boolean = false,
    ) {
        if (job?.isActive == true || (!force && startedOrderId == orderId)) return
        startedOrderId = orderId
        job =
            scope.launch {
                try {
                    driver.deliver(orderId, recipient, amount, resume).collect(onStatus)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable
                ) {
                    Twig.warn {
                        "OnrampZecDeliveryCoordinator: delivery failed outside driver handling " +
                            "(${e::class.simpleName})"
                    }
                    // The durable checkpoint outranks the exception: only it can say whether the
                    // transfer was started, so the failure is described from state, not from a message.
                    val latest = latestDeliveryCheckpoint()
                    onStatus(
                        OnrampZecDeliveryStatus.Failed(
                            stage = latest?.phase ?: resume?.phase ?: OnrampZecDeliveryPhase.NEEDS_ATTENTION,
                            fundsLocation = latest?.fundsLocation ?: FundsLocation.TRANSFER_AMBIGUOUS,
                            retryable = latest != null && !latest.transferStarted,
                        ),
                    )
                }
            }
    }

    fun resume(checkpoint: OnrampCheckpoint, force: Boolean = false) {
        val delivery = checkpoint.zecDelivery ?: return
        start(
            orderId = checkpoint.id,
            recipient = Address.parse(delivery.baseAccount),
            amount = Usdc6(BigInteger(delivery.usdcMicros)),
            resume = delivery,
            force = force,
        )
    }

    fun retry() {
        if (job?.isActive == true) return
        scope.launch {
            val checkpoint = readCheckpoint() ?: return@launch
            val delivery = checkpoint.zecDelivery
            val resumable =
                if (delivery?.phase == OnrampZecDeliveryPhase.REFUNDED_TO_BASE) {
                    checkpoint.copy(zecDelivery = delivery.restartAfterRefund()).also { storage.store(it) }
                } else {
                    checkpoint
                }
            resume(resumable, force = true)
        }
    }

    fun cancel() {
        job?.cancel()
        startedOrderId = null
    }

    private suspend fun latestDeliveryCheckpoint(): OnrampZecDeliveryCheckpoint? = readCheckpoint()?.zecDelivery

    /**
     * A checkpoint the current build cannot decode (a downgrade, a future schema) must not take the
     * screen down with it — recovery is best-effort here, and the user keeps a working Buy screen.
     */
    private suspend fun readCheckpoint(): OnrampCheckpoint? =
        try {
            storage.get()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable
        ) {
            Twig.warn { "OnrampZecDeliveryCoordinator: checkpoint could not be read (${e::class.simpleName})" }
            null
        }

    private fun OnrampZecDeliveryCheckpoint.restartAfterRefund() =
        OnrampZecDeliveryCheckpoint(
            phase = OnrampZecDeliveryPhase.FUNDS_ON_BASE,
            usdcMicros = requireNotNull(refundedUsdcMicros),
            baseAccount = baseAccount,
            acceptedCostBps = acceptedCostBps,
        )
}
