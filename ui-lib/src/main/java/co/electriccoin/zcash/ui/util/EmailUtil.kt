@file:Suppress("UnusedPrivateMember")

package co.electriccoin.zcash.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object EmailUtil {
    /**
     * Note: the caller of this method may wish to set the Intent flag to
     * [Intent.FLAG_ACTIVITY_NEW_TASK].
     *
     * @param recipientAddress E-mail address of the recipient.
     * @param messageSubject   Message subject.
     * @param messageBody      Message body.
     * @return an Intent for launching the mail app with a pre-composed message.
     */
    internal fun newMailActivityIntent(
        recipientAddress: String,
        messageSubject: String,
        messageBody: String
    ): Intent = newIntentAsUriAndExtras(recipientAddress, messageSubject, messageBody)

    // After a discussion in ASG, this is probably the most robust
    // implementation instead of using the Intent extras like EXTRA_EMAIL, EXTRA_SUBJECT, etc.
    // https://medium.com/@cketti/android-sending-email-using-intents-3da63662c58f
    // This also does a reasonable job of only displaying legitimate email apps
    internal fun newMailToUriString(
        recipientAddress: String,
        messageSubject: String,
        messageBody: String
    ): String {
        val encodedSubject = Uri.encode(messageSubject)
        val encodedBody = Uri.encode(messageBody)
        return "mailto:$recipientAddress?subject=$encodedSubject&body=$encodedBody" // $NON-NLS
    }

    // This approach combines both adding data to Uri and Extras to ensure that most of the available e-mail client
    // apps can understand the output Intent. Tested with Gmail, Proton mail, Yahoo, and Seznam.cz.
    private fun newIntentAsUriAndExtras(
        recipientAddress: String,
        messageSubject: String,
        messageBody: String
    ) = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse(newMailToUriString(recipientAddress, messageSubject, messageBody))
        putExtra(
            Intent.EXTRA_EMAIL,
            arrayOf(
                recipientAddress
            )
        )
        putExtra(Intent.EXTRA_SUBJECT, messageSubject)
        putExtra(Intent.EXTRA_TEXT, messageBody)
    }

    internal fun formatMessage(
        prefix: String? = null,
        body: String? = null,
        supportInfo: String? = null,
        suffix: String? = null,
    ): String =
        buildString {
            if (!prefix.isNullOrEmpty()) {
                appendLine(prefix)
                appendLine()
            }
            if (!body.isNullOrEmpty()) {
                appendLine(body)
                appendLine()
            }
            if (supportInfo != null) {
                appendLine(supportInfo)
                appendLine()
            }
            if (!suffix.isNullOrEmpty()) {
                appendLine(suffix)
            }
        }

    internal fun newTextShareIntent(
        subject: String,
        messageBody: String
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, messageBody)
        }

    internal fun sendEmailWithTextFallback(
        context: Context,
        recipientAddress: String,
        subject: String,
        messageBody: String
    ) {
        val emailIntent =
            newMailActivityIntent(
                recipientAddress = recipientAddress,
                messageSubject = subject,
                messageBody = messageBody
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

        runCatching {
            context.startActivity(emailIntent)
        }.onFailure {
            // Fallback to text sharing if email is not available
            val shareIntent =
                newTextShareIntent(
                    subject = subject,
                    messageBody = messageBody
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            runCatching {
                context.startActivity(shareIntent)
            }
        }
    }
}
