// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import androidx.annotation.StringRes
import cash.z.ecc.android.sdk.exception.SdkException
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.convert.ZecInputQuoteException
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.AtomicSwapHttpException
import xyz.justzappit.offramp.atomicswap.AtomicSwapService
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Why a conversion step didn't go ahead; the ones without a message of their own take the step's. */
internal enum class PrivateUsdFailure(
    @param:StringRes private val specific: Int?
) {
    NO_QUOTE_FITS(R.string.convert_zec_no_quote),
    MAKER_UNREACHABLE(R.string.convert_error_maker),
    RELAYER_UNREACHABLE(R.string.convert_error_relayer),
    ETHEREUM_UNREACHABLE(R.string.convert_error_ethereum),
    ZCASH_UNAVAILABLE(R.string.convert_error_wallet),
    DEPOSIT_UNPAYABLE(R.string.convert_error_unpayable),
    QUOTE_EXPIRED(R.string.convert_quote_ran_out),
    ALREADY_ON_CHAIN(R.string.convert_call_off_too_late),
    STORE_UNREADABLE(R.string.convert_error_store),
    DEPOSIT_UNCONFIRMED(R.string.reverse_error_unconfirmed),
    FUNDING_COST_CHANGED(R.string.reverse_error_cost_changed),
    TOO_LATE(R.string.reverse_error_too_late),

    /** Another conversion is under way: its screen is the place to be. */
    SWAP_UNDER_WAY(null),
    OTHER(null);

    fun message(
        @StringRes otherwise: Int
    ): StringResource = stringRes(specific ?: otherwise)
}

internal fun Throwable.toFailure(): PrivateUsdFailure =
    when (this) {
        is ZecInputQuoteException -> PrivateUsdFailure.NO_QUOTE_FITS
        is AtomicSwapBlockedException -> reason.toFailure()
        is AtomicSwapHttpException -> service.toFailure()
        is RpcException -> PrivateUsdFailure.ETHEREUM_UNREACHABLE
        is SdkException, is IOException -> PrivateUsdFailure.ZCASH_UNAVAILABLE
        is StoreCorruptedException -> PrivateUsdFailure.STORE_UNREADABLE
        else -> PrivateUsdFailure.OTHER
    }

/** [block]'s result, or the failure it ended in, logged as [what]. Cancellation still cancels. */
internal inline fun <T> runConversionStep(
    what: String,
    block: () -> T
): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Exception) {
        Twig.warn(ignored) { "Private USD: $what" }
        Result.failure(ignored)
    }

private fun AtomicSwapService.toFailure() =
    when (this) {
        AtomicSwapService.MAKER -> PrivateUsdFailure.MAKER_UNREACHABLE
        AtomicSwapService.RELAYER -> PrivateUsdFailure.RELAYER_UNREACHABLE
    }

private fun AtomicSwapBlock.toFailure() =
    when (this) {
        AtomicSwapBlock.SWAP_UNDER_WAY -> PrivateUsdFailure.SWAP_UNDER_WAY

        AtomicSwapBlock.QUOTE_EXPIRED -> PrivateUsdFailure.QUOTE_EXPIRED

        AtomicSwapBlock.UNDER_WAY_ON_CHAIN -> PrivateUsdFailure.ALREADY_ON_CHAIN

        AtomicSwapBlock.DEPOSIT_UNPAYABLE -> PrivateUsdFailure.DEPOSIT_UNPAYABLE

        AtomicSwapBlock.ZCASH_UNAVAILABLE -> PrivateUsdFailure.ZCASH_UNAVAILABLE

        AtomicSwapBlock.CHAIN_LAGGING, AtomicSwapBlock.CHAIN_UNREADABLE -> PrivateUsdFailure.ETHEREUM_UNREACHABLE

        AtomicSwapBlock.RELAYER_FEE -> PrivateUsdFailure.RELAYER_UNREACHABLE

        AtomicSwapBlock.DEPOSIT_UNCONFIRMED -> PrivateUsdFailure.DEPOSIT_UNCONFIRMED

        AtomicSwapBlock.FUNDING_COST_CHANGED -> PrivateUsdFailure.FUNDING_COST_CHANGED

        AtomicSwapBlock.DEADLINE_PASSED -> PrivateUsdFailure.TOO_LATE

        AtomicSwapBlock.WRONG_DEPLOYMENT,
        AtomicSwapBlock.RAILGUN_CLOSED,
        AtomicSwapBlock.CLAIM_LOCK_LAPSING,
        AtomicSwapBlock.MISMATCH,
        AtomicSwapBlock.ZCASH_REJECTED,
        AtomicSwapBlock.INDICES_IN_USE -> PrivateUsdFailure.OTHER
    }
