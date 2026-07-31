package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.screen.feedback.FeedbackEmoji
import co.electriccoin.zcash.ui.screen.support.model.SupportInfoType
import co.electriccoin.zcash.ui.util.EmailUtil

class SendSupportEmailUseCase(
    private val context: Context,
    private val getSupport: GetSupportUseCase
) {
    suspend operator fun invoke(
        emoji: FeedbackEmoji,
        message: StringResource
    ) {
        val messageBody =
            buildString {
                appendLine(
                    context.getString(
                        R.string.support_email_part_1,
                        emoji.encoding,
                        emoji.order.toString()
                    )
                )
                appendLine()
                appendLine(context.getString(R.string.support_email_part_2, message.getString(context)))
                appendLine()
                appendLine()
                appendLine(getSupport().toSupportString(SupportInfoType.entries.toSet()))
            }
        val subject = context.getString(R.string.app_name)

        EmailUtil.sendEmailWithTextFallback(
            context = context,
            recipientAddress = context.getString(R.string.support_email_address),
            subject = subject,
            messageBody = messageBody
        )
    }
}
