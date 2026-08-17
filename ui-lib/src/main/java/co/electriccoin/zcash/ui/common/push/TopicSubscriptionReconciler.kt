package co.electriccoin.zcash.ui.common.push

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface TopicSubscriptionClient {
    suspend fun subscribe(topic: String)

    suspend fun unsubscribe(topic: String)
}

internal interface TopicSubscriptionStore {
    fun desiredBindings(): Map<String, ChatPushTopic>

    fun replaceDesiredBindings(bindings: Map<String, ChatPushTopic>)

    fun subscribedTopics(): Set<String>

    fun replaceSubscribedTopics(topics: Set<String>)

    fun isDeliveryEnabled(): Boolean

    fun setDeliveryEnabled(enabled: Boolean)
}

internal enum class TopicOperation {
    SUBSCRIBE,
    UNSUBSCRIBE,
}

/** Pure, idempotent set reconciliation used by the Firebase backend. */
internal class TopicSubscriptionReconciler(
    private val client: TopicSubscriptionClient,
    private val store: TopicSubscriptionStore,
    private val onFailure: (TopicOperation) -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun reconcile(
        desired: Map<String, ChatPushTopic>,
        enabled: Boolean,
    ) = mutex.withLock {
        val effectiveDesired = if (enabled) desired else emptyMap()
        store.setDeliveryEnabled(enabled)
        // Persist routing before subscribing so a doorbell that follows the
        // successful Firebase task can always resolve its conversation.
        store.replaceDesiredBindings(effectiveDesired)

        val subscribed = store.subscribedTopics().toMutableSet()
        for (topic in subscribed - effectiveDesired.keys) {
            runCatching { client.unsubscribe(topic) }
                .onSuccess { subscribed.remove(topic) }
                .onFailure { onFailure(TopicOperation.UNSUBSCRIBE) }
        }
        for (topic in effectiveDesired.keys - subscribed) {
            runCatching { client.subscribe(topic) }
                .onSuccess { subscribed.add(topic) }
                .onFailure { onFailure(TopicOperation.SUBSCRIBE) }
        }
        store.replaceSubscribedTopics(subscribed)
    }

    /** A refreshed FCM token must explicitly reassert every desired topic. */
    suspend fun forceResubscribe() =
        mutex.withLock {
            if (!store.isDeliveryEnabled()) return@withLock
            val subscribed = store.subscribedTopics().toMutableSet()
            for (topic in store.desiredBindings().keys) {
                runCatching { client.subscribe(topic) }
                    .onSuccess { subscribed.add(topic) }
                    .onFailure { onFailure(TopicOperation.SUBSCRIBE) }
            }
            store.replaceSubscribedTopics(subscribed)
        }
}
