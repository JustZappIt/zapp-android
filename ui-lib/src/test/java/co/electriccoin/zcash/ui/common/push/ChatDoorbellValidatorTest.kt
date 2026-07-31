package co.electriccoin.zcash.ui.common.push

import co.electriccoin.zcash.ui.common.provider.ChatBlockedKeysStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatDoorbellValidatorTest {
    @Test
    fun `valid gateway envelope returns its opaque topic`() {
        val topic = "a".repeat(64)

        assertEquals(topic, ChatDoorbellValidator.validate("/topics/$topic", validData()))
    }

    @Test
    fun `malformed and unexpected payloads are rejected`() {
        assertNull(ChatDoorbellValidator.validate("/topics/not-a-topic", validData()))
        assertNull(ChatDoorbellValidator.validate("/topics/${"a".repeat(64)}", mapOf("payload" to "AAAA")))
        assertNull(
            ChatDoorbellValidator.validate(
                "/topics/${"a".repeat(64)}",
                validData() + ("title" to "x".repeat(129)),
            ),
        )
    }

    @Test
    fun `unknown disabled blocked and active conversations are suppressed`() {
        val state = ChatNotificationState()
        val blocked = FakeBlockedKeys(setOf("b".repeat(64)))
        val decider = ChatDoorbellDecider(blocked, state)
        val allowed = ChatPushTopic("a".repeat(64), "conversation", "c".repeat(64))
        val denied = allowed.copy(writerPublicKey = "b".repeat(64))

        assertFalse(decider.shouldNotify(null, deliveryEnabled = true))
        assertFalse(decider.shouldNotify(allowed, deliveryEnabled = false))
        assertFalse(decider.shouldNotify(denied, deliveryEnabled = true))
        state.setActiveConversation("conversation")
        state.setForeground(true)
        assertFalse(decider.shouldNotify(allowed, deliveryEnabled = true))
        state.setForeground(false)
        assertTrue(decider.shouldNotify(allowed, deliveryEnabled = true))
    }

    private fun validData() =
        mapOf(
            "title" to "ignored",
            "body" to "ignored",
            "payload" to "A".repeat(32),
        )

    private class FakeBlockedKeys(
        private var keys: Set<String>,
    ) : ChatBlockedKeysStorageProvider {
        override fun get() = keys

        override fun store(keys: Set<String>) {
            this.keys = keys
        }

        override fun clear() {
            keys = emptySet()
        }
    }
}
