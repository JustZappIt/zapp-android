// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.onramp.FundsLocation
import xyz.justzappit.offramp.onramp.OnrampDestination
import xyz.justzappit.offramp.onramp.OnrampStatus
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryPhase
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryStatus
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigDecimal

@PreviewScreens
@Composable
private fun PreviewConvertingToZec() =
    PreviewOnramp(
        mode = OnrampMode.CONVERTING_TO_ZEC,
        destination = OnrampDestination.ZCASH,
        delivery = OnrampZecDeliveryStatus.AwaitingZec(PREVIEW_NET_USDC),
        progress = previewCompleted(),
    )

@PreviewScreens
@Composable
private fun PreviewZecCompletion() =
    PreviewOnramp(
        mode = OnrampMode.COMPLETION,
        destination = OnrampDestination.ZCASH,
        delivery = OnrampZecDeliveryStatus.Delivered(PREVIEW_NET_USDC, "0.019", null),
        progress = previewCompleted(),
    )

@PreviewScreens
@Composable
private fun PreviewRefundedToBase() =
    PreviewOnramp(
        mode = OnrampMode.REFUNDED_TO_BASE,
        destination = OnrampDestination.ZCASH,
        delivery =
            OnrampZecDeliveryStatus.RefundedToBase(
                inputUsdc = PREVIEW_NET_USDC,
                refundedUsdc = PREVIEW_NET_USDC,
                baseAccount = Address.parse(PREVIEW_ADDRESS),
            ),
        progress = previewCompleted(),
    )

@PreviewScreens
@Composable
private fun PreviewDeliveryNeedsAttention() =
    PreviewOnramp(
        mode = OnrampMode.DELIVERY_NEEDS_ATTENTION,
        destination = OnrampDestination.ZCASH,
        delivery =
            OnrampZecDeliveryStatus.Failed(
                stage = OnrampZecDeliveryPhase.AWAITING_ZEC,
                fundsLocation = FundsLocation.NEAR_INTENT,
                retryable = false,
            ),
        progress = previewCompleted(),
    )

@PreviewScreens
@Composable
private fun PreviewConversionFailedOnBase() =
    PreviewOnramp(
        mode = OnrampMode.DELIVERY_NEEDS_ATTENTION,
        destination = OnrampDestination.ZCASH,
        delivery =
            OnrampZecDeliveryStatus.Failed(
                stage = OnrampZecDeliveryPhase.QUOTING,
                fundsLocation = FundsLocation.BASE_ACCOUNT,
                retryable = true,
            ),
        progress = previewCompleted(),
    )

private val PREVIEW_NET_USDC = Usdc6.ofWhole(BigDecimal("0.910153"))

private fun previewCompleted() =
    OnrampStatus.Completed(
        id = PREVIEW_ID,
        orderId = PREVIEW_ORDER_ID,
        netUsdc = PREVIEW_NET_USDC,
        fiatAmount = Usdc6.ofWhole(BigDecimal("100")),
        paidTx = null,
        recipientAddress = Address.parse(PREVIEW_ADDRESS),
    )
