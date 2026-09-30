// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.AndroidRuntimeException
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration.Companion.seconds

internal interface RailgunPage {
    val isOpen: Boolean

    suspend fun call(
        method: RailgunMethod,
        params: JsonElement
    ): JsonElement

    suspend fun close()
}

/** Hidden WebViews that load only this module's assets, one page at a time, each over a port of its own. */
internal class RailgunWebViewHost(
    private val context: Context,
    private val debug: Boolean,
) {
    private val mutableEvents =
        MutableSharedFlow<RailgunEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<RailgunEvent> = mutableEvents.asSharedFlow()

    // The page's messages are decoded here, off the main thread.
    private val messages by lazy { Handler(HandlerThread("railgun-page").apply { start() }.looper) }

    // Main thread only.
    private var current: Page? = null

    /** Loads a fresh page, closing the one before it. */
    suspend fun load(): RailgunPage =
        withContext(Dispatchers.Main) {
            current?.close(RailgunException.Disconnected("a new page replaced it"))
            val page = Page(createWebView())
            current = page
            page.load()
            page
        }

    suspend fun close() = withContext(Dispatchers.Main) { current?.close(RailgunException.Disconnected("closed")) }

    /** Closes the page and deletes everything it stored. */
    suspend fun wipe() =
        withContext(Dispatchers.Main) {
            current?.close(RailgunException.Disconnected("wiped"))
            forget(RailgunRequests.PAGE_ORIGIN)
            forget(RailgunRequests.LEGACY_ORIGIN)
        }

    /** Deletes the page's storage from before it had an origin of its own. */
    suspend fun forgetLegacyStorage() = withContext(Dispatchers.Main) { forget(RailgunRequests.LEGACY_ORIGIN) }

    @SuppressLint("SetJavaScriptEnabled")
    @MainThread
    private fun createWebView(): WebView =
        unavailableOnFailure {
            if (debug) WebView.setWebContentsDebuggingEnabled(true)
            WebView(context)
        }.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = Chrome()
        }

    @MainThread
    private fun forget(origin: String) = unavailableOnFailure { WebStorage.getInstance() }.deleteOrigin(origin)

    private inner class Page(
        private val view: WebView
    ) : RailgunPage {
        private val channel = RailgunChannel(::post, mutableEvents::tryEmit)

        // Main thread only.
        private var port: WebMessagePort? = null
        var isLoaded = false
            private set

        override val isOpen: Boolean get() = channel.isOpen

        @MainThread
        suspend fun load() {
            view.webViewClient = Client(this)
            view.loadUrl(RailgunRequests.PAGE_URL)
            try {
                channel.awaitReady(LOAD_TIMEOUT)
                isLoaded = true
            } finally {
                if (!isLoaded) close(RailgunException.Disconnected("the page did not load"))
            }
        }

        override suspend fun call(
            method: RailgunMethod,
            params: JsonElement
        ): JsonElement =
            try {
                channel.call(method, params)
            } catch (e: RailgunException.Timeout) {
                withContext(NonCancellable + Dispatchers.Main) { close(e) }
                throw e
            }

        override suspend fun close() = withContext(Dispatchers.Main) { close(RailgunException.Disconnected("closed")) }

        @MainThread
        fun handshake() {
            if (port != null || !isOpen) return
            val (hostPort, pagePort) = view.createWebMessageChannel()
            port = hostPort
            hostPort.setWebMessageCallback(
                object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(
                        port: WebMessagePort,
                        message: WebMessage
                    ) = channel.receive(message.data)
                },
                messages,
            )
            val hello = WebMessage(RailgunProtocol.INIT, arrayOf(pagePort))
            view.postWebMessage(hello, Uri.parse(RailgunRequests.PAGE_ORIGIN))
        }

        @MainThread
        fun close(reason: RailgunException) {
            if (current === this) current = null
            if (!isOpen) return
            channel.close(reason)
            port?.close()
            port = null
            view.destroy()
        }

        private suspend fun post(message: String) =
            withContext(Dispatchers.Main) {
                try {
                    checkNotNull(port?.takeIf { isOpen }).postMessage(WebMessage(message))
                } catch (e: IllegalStateException) {
                    throw RailgunException.Disconnected("the page closed", e)
                }
            }
    }

    private inner class Client(
        private val page: Page
    ) : WebViewClient() {
        private val assets =
            WebViewAssetLoader
                .Builder()
                .setDomain(RailgunRequests.PAGE_HOST)
                .addPathHandler(RailgunRequests.ASSETS_PATH, OwnAssets(context))
                .build()

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url
            return when (RailgunRequests.route(url.scheme, url.host, url.port)) {
                RailgunRequests.Route.OWN_ASSET -> assets.shouldInterceptRequest(url) ?: refusal(NOT_FOUND)
                RailgunRequests.Route.ALLOWED -> null
                RailgunRequests.Route.BLOCKED -> refusal(FORBIDDEN)
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ) = true

        override fun onPageFinished(
            view: WebView,
            url: String
        ) {
            if (url == RailgunRequests.PAGE_URL) page.handshake()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) = failLoading(request)

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) = failLoading(request)

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            page.close(RailgunException.Disconnected("the page's renderer stopped"))
            return true
        }

        // Without its own files the page never answers, so don't wait out the load for it.
        private fun failLoading(request: WebResourceRequest) {
            val url = request.url
            val isOwnFile =
                url.host == RailgunRequests.PAGE_HOST && url.path?.startsWith(RailgunRequests.ASSETS_PATH) == true
            if (!page.isLoaded && isOwnFile) {
                page.close(RailgunException.Disconnected("the page's ${url.path} did not load"))
            }
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

    // The page's own directory, and nothing else of the APK's assets.
    private class OwnAssets(
        context: Context
    ) : WebViewAssetLoader.PathHandler {
        private val assets = WebViewAssetLoader.AssetsPathHandler(context)

        override fun handle(path: String): WebResourceResponse? = assets.handle(RailgunRequests.ASSETS_DIR + path)
    }

    private companion object {
        const val EVENT_BUFFER = 256
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        val LOAD_TIMEOUT = 30.seconds

        fun refusal(status: Int) =
            WebResourceResponse("text/plain", "utf-8", status, "Refused", emptyMap(), ByteArray(0).inputStream())

        // The framework throws this when the WebView package is missing, or updating.
        inline fun <T> unavailableOnFailure(block: () -> T): T =
            try {
                block()
            } catch (e: AndroidRuntimeException) {
                throw RailgunException.Unavailable(e)
            }
    }
}

/** Where the page's requests go: its own assets, the allowed hosts over https, and nowhere else. */
internal object RailgunRequests {
    const val PAGE_HOST = "railgun.appassets.androidplatform.net"
    const val PAGE_ORIGIN = "https://$PAGE_HOST"
    const val ASSETS_DIR = "railgun/"
    const val ASSETS_PATH = "/assets/$ASSETS_DIR"
    const val PAGE_URL = "$PAGE_ORIGIN${ASSETS_PATH}index.html"
    const val LEGACY_ORIGIN = "https://appassets.androidplatform.net"
    private const val HTTPS = "https"
    private const val DEFAULT_PORT = -1

    // Never leave the WebView; the CSP governs them.
    private val LOCAL_SCHEMES = setOf("blob", "data")

    enum class Route { OWN_ASSET, ALLOWED, BLOCKED }

    fun route(
        scheme: String?,
        host: String?,
        port: Int
    ): Route {
        val isHttps = scheme == HTTPS && port == DEFAULT_PORT
        return when {
            isHttps && host == PAGE_HOST -> Route.OWN_ASSET
            isHttps && host in RailgunEndpoints.hosts -> Route.ALLOWED
            scheme in LOCAL_SCHEMES -> Route.ALLOWED
            else -> Route.BLOCKED
        }
    }
}
