// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.convert

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.about
import co.electriccoin.zcash.ui.screen.privateusd.offer
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class PrivateUsdConvertTermsTest {
    private val terms = PrivateUsdConvertTerms(AtomicSwapTestnet.deployment)
    private val ready = ConvertQuote.Ready(ZecQuote(offerFor(depositZat = 202_021), feeZat = 15_000))

    @Test
    fun `the review says what a maker gone quiet leaves, by the maker's deadlines and the contract's late window`() {
        val notes = terms.info(PrivateUsdConvertPhase.REVIEW).notes.filterIsInstance<StringResource.ByResource>()
        val quiet = notes.single { it.resource == R.string.convert_review_info_quiet }

        assertEquals(listOf(45.minutes.about(), 105.minutes.about(), 1.hours.about()), quiet.args)
        assertTrue(terms.info(PrivateUsdConvertPhase.AMOUNT).notes.none { it == quiet })
    }

    @Test
    fun `a duration past the hour keeps its minutes, so a maker's deadline is never told early`() {
        assertEquals(stringRes(R.string.private_usd_about_minutes, 45), 45.minutes.about())
        assertEquals(stringRes(R.string.private_usd_about_hours, 1), 60.minutes.about())
        assertEquals(stringRes(R.string.private_usd_about_hours_minutes, 1, 45), 105.minutes.about())
        assertEquals(stringRes(R.string.private_usd_about_hours, 24), 24.hours.about())
    }

    @Test
    fun `the typed total, network fee included, must fit what the wallet can spend`() {
        val form = ConvertForm(amount = zec("0.00217021"))

        assertFalse(form.isShort(Zatoshi(217_021)))
        assertTrue(form.isShort(Zatoshi(217_020)))
        assertFalse(form.isShort(null))
    }

    @Test
    fun `a quote landing once the review is up is dropped`() {
        val reviewing = ConvertForm(phase = PrivateUsdConvertPhase.REVIEW, quote = ConvertQuote.Loading)

        assertSame(reviewing, reviewing.withQuote(ready, fillsAmount = true))
    }

    @Test
    fun `the maximum fills the amount with what its quote costs in all`() {
        val form = ConvertForm(amount = zec("5")).withQuote(ready, fillsAmount = true)

        assertEquals(BigDecimal("0.00217021"), form.amount.amount)
        assertEquals(ready, form.quote)
    }

    @Test
    fun `you pay the deposit and its fee, and the review says how long the quote holds`() {
        val now = ready.quote.offer.quote.expiresAt - 272

        val amount = terms.quote(ready, PrivateUsdConvertPhase.AMOUNT, now, LocalCurrency.DOLLAR)
        val review = terms.quote(ready, PrivateUsdConvertPhase.REVIEW, now, LocalCurrency.DOLLAR)

        assertEquals(stringRes(Zatoshi(217_021)), amount.pay)
        val countdown = stringRes(R.string.convert_quote_countdown, 4L, 12L)
        assertEquals(stringRes(R.string.convert_quote_expires, countdown), amount.expiry)
        assertEquals(stringRes(R.string.convert_quote_holds, countdown), review.expiry)
    }

    @Test
    fun `an expired quote reads as run out only on review`() {
        val expired = ready.quote.offer.quote.expiresAt
        val amount = ConvertForm(quote = ready)

        assertNull(amount.message(Zatoshi(1_000_000), expired))
        assertEquals(
            stringRes(R.string.convert_quote_ran_out),
            amount.copy(phase = PrivateUsdConvertPhase.REVIEW).message(Zatoshi(1_000_000), expired),
        )
        assertFalse(amount.canGoOn(Zatoshi(1_000_000), expired))
    }

    @Test
    fun `what you receive shows in the picked currency`() {
        val receive =
            terms.quote(ready, PrivateUsdConvertPhase.AMOUNT, 0, LocalCurrency.DOLLAR).receive
                as StringResource.ByCurrencyNumber

        assertEquals(BigDecimal("0.98"), receive.amount)
    }

    private fun zec(amount: String) = NumberTextFieldInnerState.fromAmount(BigDecimal(amount))

    private fun offerFor(depositZat: Long) =
        offer(
            index = 0,
            requested = 1_000_000,
            depositZat = depositZat,
            expiresAt = 1_790_000_300,
            receives = 977_550,
            maxTotalZat = depositZat + 15_000,
        )
}
