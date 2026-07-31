package co.electriccoin.zcash.ui.common.push

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProvider
import co.electriccoin.zcash.ui.common.provider.ChatNotifier
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject

class ZappFirebaseMessagingService : FirebaseMessagingService() {
    private val chatNotifier: ChatNotifier by inject()
    private val blockedKeys: ChatBlockedKeysStorageProvider by inject()
    private val notificationState: ChatNotificationState by inject()
    private val pushRegistrar: PushRegistrar by inject()
    private val notificationTiming: ChatNotificationTiming by inject()
    private val topicStore by lazy { FcmTopicStore(applicationContext) }

    override fun onMessageReceived(message: RemoteMessage) {
        val binding =
            ChatDoorbellValidator
                .validate(message.from, message.data)
                ?.let { topicStore.desiredBindings()[it] }
        if (binding != null) notificationTiming.onFcmReceived()
        val decider = ChatDoorbellDecider(blockedKeys, notificationState)
        if (decider.shouldNotify(binding, topicStore.isDeliveryEnabled())) {
            // Payload title/body/proof are deliberately ignored. The notification
            // is generic; opening Zapp resumes authoritative Hypercore sync.
            chatNotifier.post(
                conversationId = requireNotNull(binding).conversationId,
                conversationName = null,
                senderName = null,
                content = "",
            )
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // FirebaseMessagingService may be destroyed as soon as this callback
        // returns, so do not launch reconciliation into a service-owned scope.
        // Keep the work bounded and never upload or log the registration token.
        runBlocking(Dispatchers.IO) {
            val completed =
                withTimeoutOrNull(TOKEN_RECONCILIATION_TIMEOUT_MS) {
                    runCatching { pushRegistrar.onTokenRefresh() }
                        .onFailure { Twig.warn { "FCM token refresh reconciliation failed" } }
                    true
                } ?: false
            if (!completed) Twig.warn { "FCM token refresh reconciliation timed out" }
        }
    }

    private companion object {
        const val TOKEN_RECONCILIATION_TIMEOUT_MS = 10_000L
    }
}
