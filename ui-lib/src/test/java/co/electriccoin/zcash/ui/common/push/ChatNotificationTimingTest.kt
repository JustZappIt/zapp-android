// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChatNotificationTimingTest {
    @Test
    fun `trace logs sanitized milestones for only the tapped conversation`() {
        var nowNanos = 1_000_000L
        val logs = mutableListOf<String>()
        val timing = ChatNotificationTiming(elapsedRealtimeNanos = { nowNanos }, logger = logs::add)

        timing.onFcmReceived()
        nowNanos += 2_000_000L
        timing.onNotificationPosted()
        nowNanos += 3_000_000L
        timing.onNotificationTap("target-conversation")
        nowNanos += 2_000_000L
        timing.onDeepLinkDispatched()
        timing.onSdkInitializationStarted()
        timing.onAuthenticMessageEmitted("different-conversation")
        nowNanos += 8_000_000L
        timing.onSdkInitializationFinished(success = true)
        timing.onAuthenticMessageEmitted("target-conversation")

        assertEquals(
            listOf(
                "ChatNotificationTiming phase=fcm_received elapsed_ms=0 since=fcm_receipt",
                "ChatNotificationTiming phase=notification_posted elapsed_ms=2 since=fcm_receipt",
                "ChatNotificationTiming phase=notification_tap elapsed_ms=5 since=fcm_receipt",
                "ChatNotificationTiming phase=deep_link_dispatched elapsed_ms=2 since=notification_tap",
                "ChatNotificationTiming phase=sdk_initialization_started elapsed_ms=2 since=notification_tap",
                "ChatNotificationTiming phase=sdk_initialization_finished elapsed_ms=10 since=notification_tap",
                "ChatNotificationTiming phase=authentic_message_emitted elapsed_ms=10 since=notification_tap",
            ),
            logs,
        )
        assertFalse(logs.any { it.contains("target-conversation") || it.contains("different-conversation") })

        timing.onAuthenticMessageEmitted("target-conversation")
        assertEquals(7, logs.size)
    }

    @Test
    fun `tap trace works after process restart without an FCM timestamp`() {
        val logs = mutableListOf<String>()
        val timing = ChatNotificationTiming(elapsedRealtimeNanos = { 42_000_000L }, logger = logs::add)

        timing.onNotificationTap("target-conversation")

        assertEquals(
            listOf("ChatNotificationTiming phase=notification_tap elapsed_ms=0 since=notification_tap"),
            logs,
        )
    }
}
