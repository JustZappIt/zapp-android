// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.common

import android.app.Application
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.push.ChatNotificationTiming
import co.electriccoin.zcash.ui.common.push.PushRegistrar
import co.electriccoin.zcash.ui.common.toSetupErrorCode
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ZMIdentity

class ChatBootstrap(
    private val application: Application,
    private val sdk: ZappMessagingSDK,
    private val chatContactsRepository: ChatContactsRepository,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val applicationStateProvider: ApplicationStateProvider,
    private val pushRegistrar: PushRegistrar,
    private val notificationTiming: ChatNotificationTiming,
) {
    // Bare-kit's native IPC init has main-thread affinity; running off-main
    // null-derefs in `bare_ipc_poll_init`.
    private val scope = MainScope()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val totalUnreadCount: StateFlow<Int> =
        unreadCounts
            .map { it.values.sum() }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    val identity: StateFlow<ZMIdentity?> get() = sdk.identity

    private val _pendingDisplayName = MutableStateFlow<String?>(null)
    val pendingDisplayName: StateFlow<String?> = _pendingDisplayName.asStateFlow()

    // Scrubbed code for the last derive failure (null when none / after success or retry).
    // Source of truth; [chatIdentityFailed] is derived from it so the two can't drift.
    private val _chatIdentityErrorCode = MutableStateFlow<String?>(null)
    val chatIdentityErrorCode: StateFlow<String?> = _chatIdentityErrorCode.asStateFlow()

    val chatIdentityFailed: StateFlow<Boolean> =
        _chatIdentityErrorCode
            .map { it != null }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val _isDeriving = MutableStateFlow(false)
    val isDeriving: StateFlow<Boolean> = _isDeriving.asStateFlow()

    // Bumped by [retry]. Folded into the [AutoDeriveRequest] so a retry after a failed
    // derive produces a request that is `distinctUntilChanged`-distinct from the last
    // one, without anything else needing to change.
    private val retryToken = MutableStateFlow(0)

    @Volatile private var started = false

    /** Start process-scoped messaging initialization and observers exactly once. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                notificationTiming.onSdkInitializationStarted()
                val initialized =
                    runChatCallResult("ChatBootstrap: sdk.initialize failed") {
                        sdk.initialize(application)
                    }.isSuccess
                notificationTiming.onSdkInitializationFinished(initialized)
            } finally {
                _isInitializing.value = false
            }
        }
        scope.launch { observeMessagesForUnread() }
        scope.launch { observePendingChatIdentityDerivation() }
        scope.launch { registerPushWhenIdentityReady() }
        scope.launch { reconcilePushTopicChanges() }
        scope.launch { observeApplicationLifecycle() }
    }

    private suspend fun observeApplicationLifecycle() {
        combine(_isInitializing, applicationStateProvider.isInForeground) { initializing, isForeground ->
            if (initializing) null else isForeground
        }.distinctUntilChanged()
            .collect { isForeground ->
                when (isForeground) {
                    true -> sdk.resume()
                    false -> sdk.suspend()
                    null -> Unit
                }
            }
    }

    /**
     * Records the display name the user picked during onboarding. The reactive auto-derive
     * coroutine will pick it up once the SDK is initialised and a wallet seed becomes
     * available, and derive the chat identity from that seed. Safe to call before either
     * is ready — the request is queued. Idempotent: calling with the same name is a no-op
     * and does *not* clear any in-progress error state.
     */
    fun setPendingDisplayName(displayName: String) {
        _pendingDisplayName.value = displayName
    }

    /**
     * Re-runs auto-derive after a failure. No-op if no derive is currently queued (i.e. the
     * SDK is unready, no wallet, no pending name, or an identity already exists) or if a
     * derive is currently in flight — the latter would otherwise let a spammed retry button
     * queue redundant PBKDF2 round-trips.
     */
    fun retry() {
        if (_isDeriving.value) return
        _chatIdentityErrorCode.value = null
        retryToken.update { it + 1 }
    }

    private suspend fun observePendingChatIdentityDerivation() {
        combine(
            _isInitializing,
            persistableWalletProvider.persistableWallet,
            sdk.identity,
            _pendingDisplayName,
            retryToken,
        ) { initializing, wallet, identity, name, attempt ->
            buildAutoDeriveRequest(initializing, wallet, identity, name, attempt)
        }.distinctUntilChanged()
            .collect { request ->
                if (request != null) derive(request)
            }
    }

    private fun buildAutoDeriveRequest(
        initializing: Boolean,
        wallet: PersistableWallet?,
        identity: ZMIdentity?,
        name: String?,
        attempt: Int,
    ): AutoDeriveRequest? {
        val ready = !initializing && wallet != null
        val needsDerive = identity == null && !name.isNullOrBlank()
        if (!ready || !needsDerive) return null
        return AutoDeriveRequest(wallet = wallet, displayName = name, attempt = attempt)
    }

    private suspend fun derive(request: AutoDeriveRequest) {
        _isDeriving.value = true
        try {
            val seedPhrase = request.wallet.seedPhrase.joinedString()
            runChatCallResult("ChatBootstrap: auto-derive chat identity from wallet seed") {
                sdk.restoreFromSeedPhrase(seedPhrase, request.displayName)
            }.fold(
                onSuccess = {
                    _pendingDisplayName.value = null
                    _chatIdentityErrorCode.value = null
                },
                onFailure = {
                    _chatIdentityErrorCode.value = it.toSetupErrorCode()
                },
            )
        } finally {
            _isDeriving.value = false
        }
    }

    private suspend fun observeMessagesForUnread() {
        sdk.messageReceived.collect { (conversationId, msg) ->
            notificationTiming.onAuthenticMessageEmitted(conversationId)
            if (msg.isFromMe) return@collect
            if (chatContactsRepository.isBlocked(msg.senderId)) return@collect
            unreadCounts.update { current ->
                current + (conversationId to ((current[conversationId] ?: 0) + 1))
            }
        }
    }

    fun markConversationRead(conversationId: String) {
        unreadCounts.update { it - conversationId }
    }

    // Once a chat identity exists, reconcile the complete transport-specific topic set.
    private suspend fun registerPushWhenIdentityReady() {
        sdk.identity
            .map { it != null }
            .distinctUntilChanged()
            .collect { hasIdentity -> if (hasIdentity) runCatching { pushRegistrar.sync() } }
    }

    private suspend fun reconcilePushTopicChanges() {
        sdk.pushTopicsChanged.collect {
            if (sdk.identity.value != null) runCatching { pushRegistrar.sync() }
        }
    }

    private data class AutoDeriveRequest(
        val wallet: PersistableWallet,
        val displayName: String,
        val attempt: Int,
    )
}

private fun cash.z.ecc.android.sdk.model.SeedPhrase.joinedString(): String =
    split.joinToString(" ")
