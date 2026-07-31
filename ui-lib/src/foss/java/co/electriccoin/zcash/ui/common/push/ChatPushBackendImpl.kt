package co.electriccoin.zcash.ui.common.push

import android.content.Context
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.BuildConfig
import xyz.justzappit.zappmessaging.ZappMessagingSDK

/** Explicitly opt-in persistent ntfy fallback for devices without Google services. */
class ChatPushBackendImpl(
    context: Context,
    private val sdk: ZappMessagingSDK,
) : ChatPushBackend {
    private val applicationContext = context.applicationContext
    private val pushKeys = PushKeys(applicationContext)

    override suspend fun reconcile(
        enabled: Boolean,
        topics: List<ChatPushTopic>,
    ) {
        if (!enabled) {
            ChatWakeService.stop(applicationContext)
            return
        }
        if (BuildConfig.NTFY_BASE_URL.isBlank()) {
            ChatWakeService.stop(applicationContext)
            Twig.warn { "FOSS background push is not configured" }
            return
        }
        if (sdk.identity.value != null) {
            val endpoint = BuildConfig.NTFY_BASE_URL.trimEnd('/') + "/" + pushKeys.topic
            runCatching { sdk.registerPushEndpoint(endpoint, pushKeys.p256dh, pushKeys.auth) }
                .onFailure { Twig.warn { "FOSS push endpoint registration failed" } }
        }
        ChatWakeService.start(applicationContext)
    }

    override suspend fun onTokenRefresh() = Unit
}
