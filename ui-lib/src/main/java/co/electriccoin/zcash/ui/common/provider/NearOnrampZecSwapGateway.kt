// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.datasource.AFFILIATE_ADDRESS
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapStatus
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.onramp.OnrampZecDeliveryCheckpoint
import xyz.justzappit.offramp.p2p.Usdc6
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Instant

internal data class OnrampZecSwapResult(
    val status: SwapStatus,
    val outputZec: String,
    val refundedUsdc: Usdc6?,
)

internal interface OnrampZecSwapGateway {
    suspend fun quote(account: Address, amount: Usdc6): ValidatedZecSwapQuote

    suspend fun notifyDeposit(baseTransactionHash: String, depositAddress: Address)

    suspend fun status(checkpoint: OnrampZecDeliveryCheckpoint): OnrampZecSwapResult
}

internal class NearOnrampZecSwapGateway(
    private val usdc: Address,
    private val swapDataSource: SwapDataSource,
    private val wallet: OfframpBridgeWallet,
    private val slippageTolerancePercent: BigDecimal = DEFAULT_SLIPPAGE_PERCENT,
    private val now: () -> Instant = { Clock.System.now() },
) : OnrampZecSwapGateway {
    override suspend fun quote(account: Address, amount: Usdc6): ValidatedZecSwapQuote {
        val tokens = swapDataSource.getSupportedTokens()
        val recipient = wallet.zcashAddress()
        val quote =
            swapDataSource.requestQuote(
                swapMode = SwapMode.EXACT_INPUT,
                flexInput = false,
                amount = amount.whole,
                refundAddress = account.checksumHex,
                originAsset = tokens.usdcAsset(usdc),
                destinationAddress = recipient,
                destinationAsset = tokens.zecAsset(),
                slippage = slippageTolerancePercent,
                affiliateAddress = AFFILIATE_ADDRESS,
            )
        return validateZecSwapQuote(
            quote = quote,
            amount = amount,
            account = account,
            zcashRecipient = recipient,
            slippageTolerancePercent = slippageTolerancePercent,
            now = now(),
        )
    }

    override suspend fun notifyDeposit(baseTransactionHash: String, depositAddress: Address) {
        swapDataSource.submitDepositTransaction(baseTransactionHash, depositAddress.checksumHex)
    }

    override suspend fun status(checkpoint: OnrampZecDeliveryCheckpoint): OnrampZecSwapResult {
        val tokens = swapDataSource.getSupportedTokens()
        val current = swapDataSource.checkSwapStatus(checkpoint.depositAddress.orEmpty(), tokens)
        validateZecSwapStatus(current.quote, checkpoint)
        if (current.status == SwapStatus.SUCCESS) {
            require(current.amountOutFormatted.signum() > 0) { "Swap status has non-positive ZEC output" }
        }
        val refundedUsdc =
            if (current.status == SwapStatus.REFUNDED) {
                val rawRefund = requireNotNull(current.refunded) { "Refunded swap is missing its raw USDC amount" }
                val refund =
                    Usdc6(
                        BigInteger(
                            try {
                                rawRefund.toBigIntegerExact().toString()
                            } catch (_: ArithmeticException) {
                                throw IllegalArgumentException("Refunded swap has a fractional raw USDC amount")
                            },
                        ),
                    )
                require(refund > Usdc6.ZERO) { "Refunded swap has a non-positive USDC amount" }
                require(refund <= Usdc6(BigInteger(checkpoint.usdcMicros))) {
                    "Refunded swap amount exceeds its input"
                }
                require(current.refundedFormatted?.compareTo(refund.whole) == 0) {
                    "Refunded swap raw and formatted USDC amounts disagree"
                }
                refund
            } else {
                null
            }
        return OnrampZecSwapResult(
            status = current.status,
            outputZec = current.amountOutFormatted.stripTrailingZeros().toPlainString(),
            refundedUsdc = refundedUsdc,
        )
    }

    private companion object {
        val DEFAULT_SLIPPAGE_PERCENT: BigDecimal = BigDecimal("1")
    }
}
