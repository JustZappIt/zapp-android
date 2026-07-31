package co.electriccoin.zcash.ui.common.push

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TopicSubscriptionReconcilerTest {
    @Test
    fun `reconcile subscribes new topics and removes stale topics`() =
        runTest {
            val stale = topic("a", "old")
            val fresh = topic("b", "new")
            val store = FakeStore(mutableMapOf(stale.topic to stale), mutableSetOf(stale.topic), true)
            val client = FakeClient()
            val reconciler = TopicSubscriptionReconciler(client, store)

            reconciler.reconcile(mapOf(fresh.topic to fresh), enabled = true)

            assertEquals(listOf(stale.topic), client.unsubscribed)
            assertEquals(listOf(fresh.topic), client.subscribed)
            assertEquals(setOf(fresh.topic), store.subscribedTopics())
        }

    @Test
    fun `token refresh reasserts every desired topic`() =
        runTest {
            val desired = topic("c", "conversation")
            val store = FakeStore(mutableMapOf(desired.topic to desired), mutableSetOf(desired.topic), true)
            val client = FakeClient()

            TopicSubscriptionReconciler(client, store).forceResubscribe()

            assertEquals(listOf(desired.topic), client.subscribed)
        }

    @Test
    fun `reconcile and token refresh are serialized`() =
        runTest {
            val old = topic("d", "old")
            val fresh = topic("e", "fresh")
            val store = FakeStore(mutableMapOf(old.topic to old), mutableSetOf(), true)
            val client = ConcurrentTrackingClient()
            val reconciler = TopicSubscriptionReconciler(client, store)

            val refresh = launch { reconciler.forceResubscribe() }
            val reconcile = launch { reconciler.reconcile(mapOf(fresh.topic to fresh), enabled = true) }
            refresh.join()
            reconcile.join()

            assertEquals(1, client.maxActiveOperations)
            assertEquals(setOf(fresh.topic), store.subscribedTopics())
        }

    private fun topic(
        seed: String,
        conversationId: String,
    ) = ChatPushTopic(seed.repeat(64), conversationId, "1".repeat(64))

    private class FakeClient : TopicSubscriptionClient {
        val subscribed = mutableListOf<String>()
        val unsubscribed = mutableListOf<String>()

        override suspend fun subscribe(topic: String) {
            subscribed += topic
        }

        override suspend fun unsubscribe(topic: String) {
            unsubscribed += topic
        }
    }

    private class ConcurrentTrackingClient : TopicSubscriptionClient {
        var maxActiveOperations = 0
        private var activeOperations = 0

        override suspend fun subscribe(topic: String) = trackOperation()

        override suspend fun unsubscribe(topic: String) = trackOperation()

        private suspend fun trackOperation() {
            activeOperations++
            maxActiveOperations = maxOf(maxActiveOperations, activeOperations)
            delay(1)
            activeOperations--
        }
    }

    private class FakeStore(
        private var desired: MutableMap<String, ChatPushTopic>,
        private var subscribed: MutableSet<String>,
        private var enabled: Boolean,
    ) : TopicSubscriptionStore {
        override fun desiredBindings() = desired.toMap()

        override fun replaceDesiredBindings(bindings: Map<String, ChatPushTopic>) {
            desired = bindings.toMutableMap()
        }

        override fun subscribedTopics() = subscribed.toSet()

        override fun replaceSubscribedTopics(topics: Set<String>) {
            subscribed = topics.toMutableSet()
        }

        override fun isDeliveryEnabled() = enabled

        override fun setDeliveryEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
    }
}
