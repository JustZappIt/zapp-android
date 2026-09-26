// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.justzappit.railgun.RailgunProtocol.Message
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * A WebView that is never shown and loads nothing but this module's assets, served from
 * [ORIGIN] so the page has a secure origin with IndexedDB, WebAssembly and fetch. The page gets one
 * [WebMessagePort] and all traffic goes over it; it is created on first use and again after its
 * renderer dies.
 */
internal class RailgunWebViewHost(
    private val context: Context,
    private val debug: Boolean,
) {
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val connectLock = Mutex()
    private val mutableEvents =
        MutableSharedFlow<RailgunEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<RailgunEvent> = mutableEvents.asSharedFlow()

    // Main thread only.
    private var webView: WebView? = null
    private var port: WebMessagePort? = null
    private var ready: CompletableDeferred<WebMessagePort>? = null

    suspend fun call(
        method: String,
        params: JsonObject
    ): JsonElement {
        val id = nextId.incrementAndGet()
        val response = CompletableDeferred<JsonElement>()
        pending[id] = response
        try {
            val port = connect()
            withContext(Dispatchers.Main) {
                try {
                    port.postMessage(WebMessage(RailgunProtocol.request(id, method, params)))
                } catch (_: IllegalStateException) {
                    throw RailgunException("the Railgun WebView closed")
                }
            }
            return response.await()
        } finally {
            pending.remove(id)
        }
    }

    suspend fun close() = withContext(Dispatchers.Main) { teardown("the Railgun WebView closed") }

    private suspend fun connect(): WebMessagePort =
        connectLock.withLock {
            withContext(Dispatchers.Main) { port ?: load() }
        }

    @MainThread
    private suspend fun load(): WebMessagePort {
        val loaded = CompletableDeferred<WebMessagePort>()
        ready = loaded
        webView = createWebView().also { it.loadUrl(PAGE_URL) }
        return try {
            withTimeout(LOAD_TIMEOUT) { loaded.await() }.also { port = it }
        } catch (_: TimeoutCancellationException) {
            teardown("the Railgun page did not load")
            throw RailgunException("the Railgun page did not load")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @MainThread
    private fun createWebView(): WebView {
        if (debug) WebView.setWebContentsDebuggingEnabled(true)
        val assets =
            WebViewAssetLoader
                .Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = Client(assets)
            webChromeClient = Chrome()
        }
    }

    @MainThread
    private fun handshake(view: WebView) {
        val (hostPort, pagePort) = view.createWebMessageChannel()
        hostPort.setWebMessageCallback(
            object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(
                    port: WebMessagePort,
                    message: WebMessage
                ) = onPageMessage(port, message.data)
            }
        )
        view.postWebMessage(WebMessage(RailgunProtocol.INIT, arrayOf(pagePort)), Uri.parse(ORIGIN))
    }

    @MainThread
    private fun onPageMessage(
        port: WebMessagePort,
        data: String?
    ) {
        when (val message = data?.let(RailgunProtocol::decode)) {
            Message.Ready -> ready?.complete(port)
            is Message.Event -> mutableEvents.tryEmit(message.event)
            is Message.Result -> pending[message.id]?.complete(message.result)
            is Message.Failure -> pending[message.id]?.completeExceptionally(RailgunException(message.error))
            null -> Unit
        }
    }

    @MainThread
    private fun teardown(reason: String) {
        val view = webView ?: return
        webView = null
        port?.close()
        port = null
        ready?.completeExceptionally(RailgunException(reason))
        ready = null
        pending.values.forEach { it.completeExceptionally(RailgunException(reason)) }
        view.destroy()
        mutableEvents.tryEmit(RailgunEvent.Disconnected)
    }

    private inner class Client(
        private val assets: WebViewAssetLoader
    ) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? = assets.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ) = true

        override fun onPageFinished(
            view: WebView,
            url: String
        ) {
            if (view === webView && url == PAGE_URL && port == null) handshake(view)
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            if (view === webView) teardown("the Railgun WebView's renderer stopped") else view.destroy()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (debug) {
                mutableEvents.tryEmit(RailgunEvent.Log("console ${message.messageLevel()}: ${message.message()}"))
            }
            return true
        }
    }

    private companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val PAGE_URL = "$ORIGIN/assets/railgun/index.html"
        const val EVENT_BUFFER = 256
        val LOAD_TIMEOUT = 60.seconds
    }
}
