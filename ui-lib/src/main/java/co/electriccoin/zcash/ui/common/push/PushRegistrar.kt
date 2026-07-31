package co.electriccoin.zcash.ui.common.push

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import xyz.justzappit.zappmessaging.ZappMessagingSDK

/**
 * Reconciles the complete set of background-notification capabilities after
 * identity, conversation, core-key, setting, and token lifecycle changes.
 * Firebase and ntfy details remain behind the distribution-specific backend.
 */
class PushRegistrar(
    private val sdk: ZappMessagingSDK,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val backend: ChatPushBackend,
) {
    suspend fun sync() {
        val preferences = standardPreferenceProvider()
        val enabled =
            StandardPreferenceKeys.IS_CHAT_NOTIFICATIONS_ENABLED.getValue(preferences) &&
                StandardPreferenceKeys.IS_CHAT_BACKGROUND_PUSH_ENABLED.getValue(preferences)

        if (!enabled) {
            backend.reconcile(enabled = false, topics = emptyList())
        } else if (sdk.identity.value != null) {
            // Do not erase a restored install's persisted subscriptions during
            // the cold-start window before its messaging identity has loaded.
            val snapshot =
                runCatching { sdk.getPushTopicSnapshot() }
                    .onFailure { Twig.warn { "PushRegistrar: topic snapshot unavailable" } }
                    .getOrNull()
            if (snapshot?.hydrated == true) {
                val topics =
                    snapshot.conversations
                        .asSequence()
                        .filter { it.lifecycle == TOPIC_LIFECYCLE_READY }
                        .flatMap { conversation ->
                            conversation.inboundTopics.asSequence().map { topic ->
                                ChatPushTopic(
                                    topic = topic.topic,
                                    conversationId = conversation.conversationId,
                                    writerPublicKey = topic.writerPublicKey,
                                )
                            }
                        }.toList()
                backend.reconcile(enabled = true, topics = topics)
            } else if (snapshot != null) {
                Twig.debug { "PushRegistrar: retaining subscriptions until topic hydration completes" }
            }
        }
    }

    suspend fun onTokenRefresh() = backend.onTokenRefresh()

    private companion object {
        const val TOPIC_LIFECYCLE_READY = "ready"
    }
}
