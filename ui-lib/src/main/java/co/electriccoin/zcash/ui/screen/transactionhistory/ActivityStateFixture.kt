package co.electriccoin.zcash.ui.screen.transactionhistory

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResourceColor
import co.electriccoin.zcash.ui.design.util.StyledStringStyle
import co.electriccoin.zcash.ui.design.util.TickerLocation.HIDDEN
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle
import java.util.UUID

object ActivityStateFixture {
    @Suppress("MagicNumber")
    fun new() =
        ActivityState(
            bigIcon = R.drawable.ic_transaction_sent,
            smallIcon = R.drawable.ic_transaction_provider_near,
            title = stringRes("Transaction Title"),
            subtitle = stringRes("Transaction subtitle"),
            isShielded = true,
            value =
                stringRes(R.string.transaction_history_plus, stringRes(Zatoshi(10000000), HIDDEN))
                    .withStyle(
                        StyledStringStyle(
                            color = StringResourceColor.POSITIVE
                        )
                    ),
            fiatValue = stringRes("$2.50"),
            onClick = {},
            key = UUID.randomUUID().toString(),
            isUnread = true,
            onDisplayed = {}
        )
}
