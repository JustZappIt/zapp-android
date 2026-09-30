// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapState
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapTestnet
import co.electriccoin.zcash.ui.common.privateusd.LocalCurrency
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdActivityData
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdConversion
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdPendingSend
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdSendRecord
import co.electriccoin.zcash.ui.common.privateusd.privateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.tokenAmount
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.reverse.stageLabel
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.atomicswap.AtomicSwapOutcome
import xyz.justzappit.offramp.atomicswap.NothingSentCause
import xyz.justzappit.offramp.atomicswap.RefundCause
import xyz.justzappit.offramp.atomicswap.ReversePhase
import xyz.justzappit.offramp.atomicswap.ZcashTxId
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunDestination
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateUsdActivityMapperTest {
    private val mapper = PrivateUsdActivityMapper()
    private val opened = mutableListOf<Any>()

    @Test
    fun `what moved no money isn't activity`() {
        val rows =
            rows(
                PrivateUsdActivityData.ToUsd(
                    toUsd(index = 0, at = 10, AtomicSwapOutcome.NothingSent(NothingSentCause.QUOTE_EXPIRED))
                ),
                PrivateUsdActivityData.ToZec(toZec(index = 1, ReversePhase.CANCELLED)),
                PrivateUsdActivityData.ToZec(toZec(index = 2, ReversePhase.QUOTED)),
            )

        assertEquals(emptyList(), rows)
    }

    @Test
    fun `each kind of conversion is named for how it ended`() {
        val rows =
            rows(
                PrivateUsdActivityData.ToUsd(toUsd(index = 0, at = 10, AtomicSwapOutcome.Paid)),
                PrivateUsdActivityData.ToUsd(
                    toUsd(index = 1, at = 20, AtomicSwapOutcome.Refunded(REFUND, RefundCause.MAKER_CANCELLED))
                ),
                PrivateUsdActivityData.ToZec(toZec(index = 2, ReversePhase.COMPLETE)),
                PrivateUsdActivityData.ToZec(toZec(index = 3, ReversePhase.REFUNDED)),
                PrivateUsdActivityData.ToZec(toZec(index = 4, ReversePhase.SETTLING)),
            )

        assertEquals(
            listOf(
                R.string.private_usd_activity_converted,
                R.string.private_usd_activity_refunded,
                R.string.private_usd_activity_converted_zec,
                R.string.private_usd_activity_refunded,
                R.string.private_usd_converting_zec,
            ),
            rows.map { (it.title as StringResource.ByResource).resource },
        )
        assertEquals(
            listOf(PrivateUsdActivityTone.IN, PrivateUsdActivityTone.NEUTRAL, PrivateUsdActivityTone.OUT),
            rows.take(3).map { it.tone },
        )
    }

    @Test
    fun `a paid conversion opens its payout, and one under way opens its progress`() {
        val paid = toUsd(index = 0, at = 10, AtomicSwapOutcome.Paid).copy(payoutTx = TxHash.fromHex(PAYOUT))
        val underWay = toUsd(index = 1, at = 20, outcome = null)
        val conversion = PrivateUsdConversion.ToUsd(AtomicSwapState(record = underWay), confirmationsNeeded = 3)
        val rows =
            rows(
                PrivateUsdActivityData.ToUsd(paid),
                PrivateUsdActivityData.ToUsd(underWay),
                conversion = conversion,
            )

        rows.forEach { checkNotNull(it.onClick).invoke() }

        assertEquals(listOf(AtomicSwapTestnet.deployment.explorerTxUrl + PAYOUT, conversion), opened)
        assertEquals(conversion.swap.stageDetail(3), rows.last().detail)
    }

    @Test
    fun `a conversion back to ZEC shows what it took from the private balance, and its progress while under way`() {
        val record = toZec(index = 2, ReversePhase.AWAITING_READY)
        val conversion = PrivateUsdConversion.ToZec(record, problem = null)
        val row = rows(PrivateUsdActivityData.ToZec(record), conversion = conversion).single()

        row.onClick?.invoke()

        val paid = tokenAmount(BigInteger.valueOf(1_000_000), AtomicSwapTestnet.deployment.privateUsdToken)
        assertEquals(stringRes(R.string.private_usd_activity_out, paid).asPrivacySensitive(), row.amount)
        assertEquals(stringRes(ReversePhase.AWAITING_READY.stageLabel()), row.detail)
        assertEquals(listOf<Any>(conversion), opened)
    }

    @Test
    fun `a record in a token this build doesn't know shows without an amount`() {
        val paid = toUsd(index = 0, at = 10, AtomicSwapOutcome.Paid)
        val unknown = paid.copy(quote = paid.quote.copy(token = Address.parse(UNKNOWN_TOKEN)))
        val row = rows(PrivateUsdActivityData.ToUsd(unknown)).single()

        assertNull(row.amount)
    }

    @Test
    fun `the amounts show in the user's currency beside the dollars only when it isn't the dollar`() {
        val data =
            arrayOf(
                PrivateUsdActivityData.ToUsd(toUsd(index = 0, at = 10, AtomicSwapOutcome.Paid)),
                PrivateUsdActivityData.ToZec(toZec(index = 1, ReversePhase.COMPLETE)),
                PrivateUsdActivityData.Sent(send(at = 20)),
            )
        val rupees = LocalCurrency(FiatCurrency("INR"), "₹", BigDecimal("83.5"))

        assertTrue(rows(*data, currency = rupees).all { it.local != null })
        assertTrue(rows(*data).all { it.local == null })
    }

    @Test
    fun `a send not yet in a block says so, and one still proving has nothing to open`() {
        val proving =
            PrivateUsdPendingSend("p", TOKEN, amount = BigInteger.valueOf(2_000_000), to = RECIPIENT, startedAt = 40)
        val rows =
            rows(
                PrivateUsdActivityData.Proving(proving),
                PrivateUsdActivityData.Sent(send(at = 30).copy(confirmed = false)),
            )

        assertEquals(
            listOf(R.string.private_usd_activity_sending, R.string.private_usd_activity_unconfirmed),
            rows.map { ((it.detail as StringResource.ByResource).args[1] as StringResource.ByResource).resource },
        )
        assertNull(rows.first().onClick)
        assertNotNull(rows.last().onClick)
    }

    @Test
    fun `a send of a token that isn't a dollar is left out`() {
        val rows =
            rows(
                PrivateUsdActivityData.Sent(send(2).copy(token = Address.parse(UNKNOWN_TOKEN))),
                PrivateUsdActivityData.Sent(send(3).copy(token = Address.parse(WETH))),
            )

        assertEquals(emptyList(), rows)
    }

    private fun rows(
        vararg data: PrivateUsdActivityData,
        conversion: PrivateUsdConversion? = null,
        currency: LocalCurrency = LocalCurrency.DOLLAR,
    ) = data.mapNotNull {
        mapper.createState(
            data = it,
            deployment = AtomicSwapTestnet.deployment,
            conversion = conversion,
            currency = currency,
            onOpenConversion = { conversion -> opened += conversion },
            onOpenUrl = { url -> opened += url },
        )
    }

    private fun send(at: Long) =
        PrivateUsdSendRecord(
            txHash = TxHash.fromHex("0x" + "ab".repeat(32)),
            token = TOKEN,
            amount = BigInteger.valueOf(1_000_000),
            to = RailgunDestination.Public(Address.parse("0x1c7f9a756b08753cf8da94d394659134bb8c5539")),
            sentAt = at,
        )

    private companion object {
        val TOKEN = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8")
        val RECIPIENT =
            RailgunDestination.Private(
                RailgunAddress(
                    "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                        "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx"
                )
            )
        const val WETH = "0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14"
        const val UNKNOWN_TOKEN = "0x0000000000000000000000000000000000000001"
        val PAYOUT = "0x" + "a1".repeat(32)
        val REFUND = ZcashTxId.parse("aa".repeat(32))
    }
}
