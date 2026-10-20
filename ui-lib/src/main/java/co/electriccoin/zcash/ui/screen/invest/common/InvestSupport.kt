package co.electriccoin.zcash.ui.screen.invest.common

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.chat.SupportChatArgs

/** Opens support chat with the reference prefilled, in English for the support team. */
internal object InvestSupport {
    fun contact(
        navigationRouter: NavigationRouter,
        kind: Kind,
        reference: String?,
    ) = navigationRouter.forward(
        SupportChatArgs(
            prefilledMessage = if (reference == null) "${kind.subject}." else "${kind.subject}. Reference: $reference",
        ),
    )

    enum class Kind(
        val subject: String,
    ) {
        BUY("My Invest buy needs attention"),
        SELL("My Invest sell needs attention"),
        RECORDS("Invest can't read its trade records on my phone"),
    }
}
