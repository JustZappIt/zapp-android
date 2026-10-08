// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.exception.TorUnavailableException
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.justzappit.atomicswap.AtomicSwapException
import xyz.justzappit.atomicswap.PendingToken
import xyz.justzappit.atomicswap.PrivacyPass
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.BlindedToken
import xyz.justzappit.offramp.atomicswap.PrivacyPassClient
import xyz.justzappit.offramp.atomicswap.SwapTokenIssuer
import xyz.justzappit.offramp.atomicswap.SwapTokenState
import xyz.justzappit.offramp.atomicswap.SwapTokenStore
import xyz.justzappit.offramp.atomicswap.SwapTokens
import xyz.justzappit.offramp.atomicswap.TokenChallenge

/** The makers' Privacy Pass tokens, one supply per pinned issuer, fetched over Tor only. */
class AtomicSwapTokens(
    private val deployments: AtomicSwapDeployments,
    private val httpClientProvider: HttpClientProvider,
    private val store: SwapTokenStore,
    private val scope: CoroutineScope = swapScope(),
) {
    private val supplies = mutableMapOf<SwapTokenIssuer, SwapTokens>()

    fun source(issuer: SwapTokenIssuer): SwapTokens =
        synchronized(supplies) {
            supplies.getOrPut(issuer) {
                SwapTokens(issuer, ::torClient, PrivacyPassTokens, AtomicSwapAttestation(issuer.name), store)
            }
        }

    // The SDK offers a Tor client only while Tor or exchange rates are on.
    private suspend fun torClient(): HttpClient =
        try {
            httpClientProvider.createTor()
        } catch (e: TorUnavailableException) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_NEED_TOR, "Tor is off", e)
        }

    /** Fetches the current deployment's tokens ahead, as a quote is asked for and as a conversion ends. */
    fun prefetch() {
        val issuer = deployments.current?.swap?.tokenIssuer ?: return
        scope.launch {
            try {
                source(issuer).prefetch()
            } catch (e: AtomicSwapBlockedException) {
                Twig.info { "Atomic swap: no tokens fetched ahead, ${e.message}" }
            }
        }
    }
}

/** The tokens held for the next conversions, encrypted; an unreadable store starts over with none. */
class SwapTokenStoreImpl(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : SwapTokenStore {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, PREF_KEY, SwapTokenState.serializer())

    override suspend fun load(): SwapTokenState =
        try {
            store.get() ?: SwapTokenState()
        } catch (e: StoreCorruptedException) {
            Twig.error(e) { "Atomic swap: the token store is unreadable" }
            SwapTokenState()
        }

    override suspend fun save(state: SwapTokenState) = store.set(state)

    private companion object {
        const val PREF_KEY = "atomicswap_tokens_v1"
    }
}

/** RFC 9578's client math from libzecswap; a request it can't make is the maker's ask turned down. */
object PrivacyPassTokens : PrivacyPassClient {
    override fun challenge(header: String): TokenChallenge =
        refusing { PrivacyPass.readChallenge(header).let { TokenChallenge(it.challenge, it.issuer, it.tokenKey) } }

    override fun blind(
        challenge: ByteArray,
        tokenKey: ByteArray
    ): BlindedToken =
        refusing { PrivacyPass.blind(challenge, tokenKey).let { BlindedToken(it.blinded, it.pending.bytes) } }

    override fun finalize(
        pending: ByteArray,
        tokenKey: ByteArray,
        blindSignature: ByteArray
    ): ByteArray = refusing { PrivacyPass.finalize(PendingToken(pending), tokenKey, blindSignature) }

    override fun authorization(token: ByteArray): String = refusing { PrivacyPass.authorization(token) }

    private inline fun <T> refusing(block: () -> T): T =
        try {
            block()
        } catch (e: AtomicSwapException) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_REFUSED, e.message.orEmpty(), e)
        }
}
