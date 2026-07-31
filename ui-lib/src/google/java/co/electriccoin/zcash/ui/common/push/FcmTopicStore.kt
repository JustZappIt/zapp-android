package co.electriccoin.zcash.ui.common.push

import android.content.Context

internal class FcmTopicStore(
    context: Context,
) : TopicSubscriptionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun desiredBindings(): Map<String, ChatPushTopic> =
        preferences.all
            .mapNotNull { (key, rawValue) ->
                if (!key.startsWith(BINDING_PREFIX)) return@mapNotNull null
                val topic = key.removePrefix(BINDING_PREFIX)
                val parts = (rawValue as? String)?.split(SEPARATOR, limit = 2) ?: return@mapNotNull null
                if (parts.size != 2) return@mapNotNull null
                topic to ChatPushTopic(topic, parts[0], parts[1])
            }.toMap()

    override fun replaceDesiredBindings(bindings: Map<String, ChatPushTopic>) {
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(BINDING_PREFIX) }
            .forEach(editor::remove)
        bindings.forEach { (topic, binding) ->
            editor.putString(BINDING_PREFIX + topic, binding.conversationId + SEPARATOR + binding.writerPublicKey)
        }
        editor.commit()
    }

    override fun subscribedTopics(): Set<String> {
        val topics = preferences.getStringSet(SUBSCRIBED_TOPICS, emptySet())
        return topics.orEmpty().toSet()
    }

    override fun replaceSubscribedTopics(topics: Set<String>) {
        preferences.edit().putStringSet(SUBSCRIBED_TOPICS, topics).apply()
    }

    override fun isDeliveryEnabled(): Boolean = preferences.getBoolean(DELIVERY_ENABLED, false)

    override fun setDeliveryEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(DELIVERY_ENABLED, enabled).commit()
    }

    private companion object {
        const val PREFERENCES = "zapp_fcm_topics"
        const val BINDING_PREFIX = "binding:"
        const val SUBSCRIBED_TOPICS = "subscribed_topics"
        const val DELIVERY_ENABLED = "delivery_enabled"
        const val SEPARATOR = "\n"
    }
}
