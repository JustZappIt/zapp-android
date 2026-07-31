package co.electriccoin.zcash.ui.common.push

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.MainActivity
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.ChatNotifier
import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import co.electriccoin.zcash.ui.screen.chat.model.ChatConversation
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.byPublicKey
import co.electriccoin.zcash.ui.screen.chat.model.resolveDisplayName
import co.electriccoin.zcash.ui.screen.chat.model.resolveSenderName
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ZMMessage
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Embedded doorbell receiver (no separate distributor app) — the no-Google
 * equivalent of what Keet ships. A `remoteMessaging` foreground service holds a
 * long-lived ntfy stream; on a ping it wakes the messaging worklet, pulls the
 * real (E2E) message and notifies via the shared [ChatNotifier]. Started and
 * stopped by [PushRegistrar] off the chat-notifications toggle.
 */
@Suppress("TooManyFunctions")
class ChatWakeService : Service() {
    private val sdk: ZappMessagingSDK by inject()
    private val pushKeys by lazy { PushKeys(applicationContext) }
    private val chatNotifier: ChatNotifier by inject()
    private val chatContacts: ChatContactsRepository by inject()
    private val applicationStateProvider: ApplicationStateProvider by inject()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Worklet IPC has main-thread affinity (see ChatBootstrap).
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val statePrefs by lazy { getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE) }

    @Volatile private var running = true
    private var listenerJob: Job? = null
    private var lastWakeAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                0
            },
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        running = true
        if (listenerJob?.isActive != true) {
            listenerJob = ioScope.launch { listenLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ioScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }

    private fun listenLoop() {
        val base = BuildConfig.NTFY_BASE_URL.trimEnd('/')
        val topic = pushKeys.topic
        var backoff = INITIAL_BACKOFF_MS
        while (running) {
            runCatching { streamOnce(base, topic) { backoff = INITIAL_BACKOFF_MS } }
                .onFailure { Twig.warn(it) { "ChatWakeService: ntfy stream error" } }
            if (running) {
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun streamOnce(
        base: String,
        topic: String,
        onConnected: () -> Unit,
    ) {
        val connection =
            (URL("$base/$topic/json").openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 0 // long-lived stream; ntfy keepalives hold it open
            }
        try {
            connection.inputStream.bufferedReader().use { reader ->
                onConnected()
                readStream(reader)
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun readStream(reader: BufferedReader) {
        while (running) {
            val line = reader.readLine() ?: break
            if (line.contains(MESSAGE_EVENT)) onPing()
        }
    }

    private fun onPing() {
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < WAKE_DEBOUNCE_MS) return
        lastWakeAt = now
        mainScope.launch {
            runCatching { pullAndNotify() }
                .onFailure { Twig.warn(it) { "ChatWakeService: wake/pull failed" } }
        }
    }

    private suspend fun pullAndNotify() {
        sdk.initialize(applicationContext)
        sdk.resume()
        try {
            delay(WAKE_INITIAL_REPLICATION_DELAY_MS)
            val deadline = System.currentTimeMillis() + WAKE_REPLICATION_WINDOW_MS
            do {
                if (pullOnceAndNotify()) return
                delay(WAKE_RETRY_DELAY_MS)
            } while (System.currentTimeMillis() < deadline)
        } finally {
            if (!applicationStateProvider.isInForeground.first()) sdk.suspend()
        }
    }

    private suspend fun pullOnceAndNotify(): Boolean {
        var posted = false
        runCatching { sdk.refreshConversations() }
        val contactsByPublicKey =
            sdk.contacts.value
                .map { ChatContact(publicKey = it.publicKey, name = it.name) }
                .byPublicKey()
        for (conversation in sdk.conversations.value) {
            val latest =
                runCatching { sdk.getMessages(conversation.id, RECENT_LIMIT) }
                    .getOrNull()
                    ?.maxByOrNull { it.timestamp }
            if (latest != null && shouldPost(conversation.id, latest)) {
                val resolvedConversation = ChatConversation.from(conversation)
                val resolvedMessage = ChatMessage.from(latest).resolveSenderName(contactsByPublicKey)
                chatNotifier.post(
                    conversation.id,
                    resolvedConversation.resolveDisplayName(contactsByPublicKey),
                    resolvedMessage.senderName,
                    latest.content,
                )
                statePrefs.edit().putLong(STATE_TS_PREFIX + conversation.id, latest.timestamp).apply()
                posted = true
            }
        }
        return posted
    }

    private suspend fun shouldPost(
        conversationId: String,
        latest: ZMMessage,
    ): Boolean =
        !latest.isFromMe &&
            !chatContacts.isBlocked(latest.senderId) &&
            latest.timestamp > statePrefs.getLong(STATE_TS_PREFIX + conversationId, 0L)

    private fun createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(getString(R.string.chat_push_service_channel_name))
                .build(),
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle(getString(R.string.chat_push_service_title))
            .setContentText(getString(R.string.chat_push_service_body))
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    private fun contentIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                action = OPEN_BACKGROUND_DELIVERY_ACTION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        return PendingIntent.getActivity(
            this,
            CONTENT_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ChatWakeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatWakeService::class.java))
        }

        private const val CHANNEL_ID = "zapp_push_service"
        private const val OPEN_BACKGROUND_DELIVERY_ACTION = "xyz.justzappit.zapp.OPEN_BACKGROUND_DELIVERY"
        private const val NOTIFICATION_ID = 2
        private const val CONTENT_INTENT_REQUEST_CODE = 2
        private const val STATE_PREFS = "zapp_push_state"
        private const val STATE_TS_PREFIX = "ts_"
        private const val MESSAGE_EVENT = "\"event\":\"message\""
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val WAKE_DEBOUNCE_MS = 8_000L
        private const val WAKE_INITIAL_REPLICATION_DELAY_MS = 2_000L
        private const val WAKE_REPLICATION_WINDOW_MS = 30_000L
        private const val WAKE_RETRY_DELAY_MS = 2_000L
        private const val RECENT_LIMIT = 5
    }
}
