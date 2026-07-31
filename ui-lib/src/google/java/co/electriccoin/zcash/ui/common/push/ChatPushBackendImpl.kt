package co.electriccoin.zcash.ui.common.push

import android.content.Context
import co.electriccoin.zcash.spackle.Twig
import com.google.firebase.FirebaseApp

/** Store/internal implementation: reconcile opaque Hypercore topics with FCM. */
class ChatPushBackendImpl(
    context: Context,
) : ChatPushBackend {
    private val applicationContext = context.applicationContext
    private val store = FcmTopicStore(applicationContext)
    private val reconciler =
        TopicSubscriptionReconciler(
            client = FirebaseTopicSubscriptionClient(applicationContext),
            store = store,
            onFailure = { operation -> Twig.warn { "FCM topic ${operation.name.lowercase()} failed" } },
        )

    override fun initialize() {
        val firebaseUnavailable =
            FirebaseApp.getApps(applicationContext).isEmpty() &&
                FirebaseApp.initializeApp(applicationContext) == null
        if (firebaseUnavailable) {
            Twig.warn { "Firebase is unavailable; background chat notifications are disabled" }
        }
    }

    override suspend fun reconcile(
        enabled: Boolean,
        topics: List<ChatPushTopic>,
    ) {
        reconciler.reconcile(
            desired = topics.associateBy(ChatPushTopic::topic),
            enabled = enabled,
        )
    }

    override suspend fun onTokenRefresh() = reconciler.forceResubscribe()
}
