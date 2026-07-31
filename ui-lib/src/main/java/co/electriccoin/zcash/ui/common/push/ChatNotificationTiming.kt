// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.push

import android.os.SystemClock
import co.electriccoin.zcash.spackle.Twig

/**
 * Process-local, sanitized timing for the notification reconciliation path.
 *
 * The target conversation is retained only in memory to correlate the first
 * authentic SDK event. It is never included in logs, along with topics, keys,
 * payloads, message data, or names.
 */
class ChatNotificationTiming internal constructor(
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val logger: (String) -> Unit = { message -> Twig.info { message } },
) {
    private var fcmReceivedAtNanos: Long? = null
    private var notificationTapAtNanos: Long? = null
    private var targetConversationId: String? = null

    @Synchronized
    fun onFcmReceived() {
        fcmReceivedAtNanos = elapsedRealtimeNanos()
        logPhase(PHASE_FCM_RECEIVED, fcmReceivedAtNanos, ORIGIN_FCM_RECEIPT)
    }

    @Synchronized
    fun onNotificationPosted() {
        val origin = fcmReceivedAtNanos ?: return
        logPhase(PHASE_NOTIFICATION_POSTED, origin, ORIGIN_FCM_RECEIPT)
    }

    @Synchronized
    fun onNotificationTap(conversationId: String) {
        val now = elapsedRealtimeNanos()
        val fcmOrigin = fcmReceivedAtNanos
        notificationTapAtNanos = now
        targetConversationId = conversationId
        logPhase(
            phase = PHASE_NOTIFICATION_TAP,
            originNanos = fcmOrigin ?: now,
            originName = if (fcmOrigin == null) ORIGIN_NOTIFICATION_TAP else ORIGIN_FCM_RECEIPT,
            nowNanos = now,
        )
    }

    @Synchronized
    fun onDeepLinkDispatched() {
        logAfterTap(PHASE_DEEP_LINK_DISPATCHED)
    }

    @Synchronized
    fun onSdkInitializationStarted() {
        logAfterTap(PHASE_SDK_INITIALIZATION_STARTED)
    }

    @Synchronized
    fun onSdkInitializationFinished(success: Boolean) {
        logAfterTap(if (success) PHASE_SDK_INITIALIZATION_FINISHED else PHASE_SDK_INITIALIZATION_FAILED)
    }

    @Synchronized
    fun onAuthenticMessageEmitted(conversationId: String) {
        if (conversationId != targetConversationId) return
        logAfterTap(PHASE_AUTHENTIC_MESSAGE_EMITTED)
        clearTrace()
    }

    private fun logAfterTap(phase: String) {
        val origin = notificationTapAtNanos ?: return
        logPhase(phase, origin, ORIGIN_NOTIFICATION_TAP)
    }

    private fun logPhase(
        phase: String,
        originNanos: Long?,
        originName: String,
        nowNanos: Long = elapsedRealtimeNanos(),
    ) {
        val elapsedMs = originNanos?.let { ((nowNanos - it).coerceAtLeast(0L)) / NANOS_PER_MILLI } ?: 0L
        logger("ChatNotificationTiming phase=$phase elapsed_ms=$elapsedMs since=$originName")
    }

    private fun clearTrace() {
        fcmReceivedAtNanos = null
        notificationTapAtNanos = null
        targetConversationId = null
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val ORIGIN_FCM_RECEIPT = "fcm_receipt"
        const val ORIGIN_NOTIFICATION_TAP = "notification_tap"
        const val PHASE_FCM_RECEIVED = "fcm_received"
        const val PHASE_NOTIFICATION_POSTED = "notification_posted"
        const val PHASE_NOTIFICATION_TAP = "notification_tap"
        const val PHASE_DEEP_LINK_DISPATCHED = "deep_link_dispatched"
        const val PHASE_SDK_INITIALIZATION_STARTED = "sdk_initialization_started"
        const val PHASE_SDK_INITIALIZATION_FINISHED = "sdk_initialization_finished"
        const val PHASE_SDK_INITIALIZATION_FAILED = "sdk_initialization_failed"
        const val PHASE_AUTHENTIC_MESSAGE_EMITTED = "authentic_message_emitted"
    }
}
