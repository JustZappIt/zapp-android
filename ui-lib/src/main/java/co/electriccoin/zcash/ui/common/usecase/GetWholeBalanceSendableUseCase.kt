package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import java.math.BigDecimal

/**
 * What the selected account can send to one recipient once the network fee comes out of its whole spendable
 * balance — the amount an ALL preset sends.
 *
 * ZIP 317 prices a send by the notes it spends, so the fee of spending everything is only known once the wallet
 * has tried: proposing the whole balance fails short by exactly that fee, which librustzcash names, and the
 * balance less it then drains the account with no change. Nothing is kept or sent; the caller builds the real
 * payment for the returned amount, so its review shows the fee taken out.
 */
class GetWholeBalanceSendableUseCase(
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
) {
    /**
     * [destination] shapes the fee — a transparent or TEX recipient costs more than a shielded one — and defaults
     * to the account's own unified address, which a shielded recipient costs the same as.
     */
    @Throws(InsufficientFundsException::class)
    suspend operator fun invoke(
        destination: (WalletAccount) -> WalletAddress = { it.unified.address },
    ): Zatoshi {
        val account = accountDataSource.getSelectedAccount()
        val to = destination(account)
        var amount = account.spendableShieldedBalance
        repeat(MAX_ATTEMPTS) {
            if (amount.value > 0) {
                val shortfall = shortfallOf(account, to, amount) ?: return amount
                amount = Zatoshi((amount.value - shortfall.value).coerceAtLeast(0))
            }
        }
        throw InsufficientFundsException()
    }

    /** Null when [amount] can be sent, else how far short it falls; an unnamed shortfall is passed on. */
    private suspend fun shortfallOf(
        account: WalletAccount,
        to: WalletAddress,
        amount: Zatoshi,
    ): Zatoshi? =
        try {
            proposalDataSource.createProposal(account, ZecSend(to, amount, Memo(""), proposal = null))
            null
        } catch (e: InsufficientFundsException) {
            e.shortfall ?: throw e
        }

    private companion object {
        // The whole balance, then the balance less its fee; a third covers a balance that moved in between.
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * [this] in ZEC to the last zatoshi. The SDK's `convertZatoshiToZec` rounds to the formatter's six places, which
 * can round a whole balance up past what the wallet holds.
 */
internal fun Zatoshi.toZecExact(): BigDecimal = BigDecimal.valueOf(value, ZEC_DECIMALS)

private const val ZEC_DECIMALS = 8
