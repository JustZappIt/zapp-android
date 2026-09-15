// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.liveness.LivenessConfig
import xyz.justzappit.offramp.liveness.LivenessReader
import xyz.justzappit.offramp.liveness.LivenessReturn
import xyz.justzappit.offramp.liveness.LivenessReturnSignal
import xyz.justzappit.offramp.liveness.LivenessVerificationDriver
import xyz.justzappit.offramp.liveness.LivenessWidgetClient
import xyz.justzappit.offramp.p2p.CurrencyCode

/**
 * Swift-friendly facade over the liveness standing read and the selfie verification driver,
 * shaped like [AppleReputationClient]: one run at a time, and the widget's redirect handed to
 * whichever run is waiting on it.
 *
 * The redirect is load-bearing here in a way the Reclaim one is not — the one-time `code` on it
 * is the only handle the app ever gets on the result — so a return that arrives with no run live
 * is not dropped: Swift resumes it through [resume].
 */
class AppleLivenessClient private constructor(
    private val smartAccounts: SmartOfframpAccountProvider,
    private val reader: LivenessReader,
    private val driver: LivenessVerificationDriver,
) {
    private val runLock = Mutex()

    /** The live run's signal; see [AppleReputationClient] for why a state flow rather than a field. */
    private val activeSignal = MutableStateFlow<LivenessReturnSignal?>(null)

    /** False on a network with no integrator, where the row must not render at all. */
    val isAvailable: Boolean get() = reader.isAvailable

    /** Null where [isAvailable] is false. One failed read fails, never a zeroed standing. */
    @Throws(Exception::class)
    suspend fun standing(): AppleLivenessStanding? = reader.read(smartAccounts.resolve().address)?.toApple()

    /**
     * Opens a widget session and waits for its redirect. [nonce] is Swift's, random per run, so
     * a redirect from any other session — ours or not — fails the state check.
     */
    fun verify(currencyCode: String, nonce: String): Flow<AppleLivenessStatus> {
        val currency =
            CurrencyCode.fromCodeOrNull(currencyCode)
                ?: return flow { emit(AppleLivenessStatus.Failed(UNKNOWN_REASON)) }
        return admitted {
            val signal = LivenessReturnSignal()
            activeSignal.value = signal
            try {
                driver.verify(currency, nonce, signal).collect { emit(it.toApple()) }
            } finally {
                // Only ever clears this run's own signal, never one a later run has published.
                activeSignal.compareAndSet(signal, null)
            }
        }
    }

    /**
     * Hands the widget's redirect to the run waiting on it. False when none is — a cold start,
     * or a run the user cancelled before the check finished — and Swift resumes it instead.
     */
    fun deliverReturn(code: String?, error: String?, state: String?): Boolean {
        val signal = activeSignal.value ?: return false
        signal.deliver(LivenessReturn(code = code, error = error, state = state))
        return true
    }

    /** Finishes a check whose redirect outlived the run that started it. */
    fun resume(code: String?, error: String?, state: String?): Flow<AppleLivenessStatus> =
        admitted {
            // Nothing to hold open: the user has already been and come back.
            activeSignal.value = null
            driver.resume(LivenessReturn(code = code, error = error, state = state)).collect { emit(it.toApple()) }
        }

    private fun admitted(block: suspend FlowCollector<AppleLivenessStatus>.() -> Unit): Flow<AppleLivenessStatus> =
        singleLivenessRun(runLock, block)

    companion object {
        /** A run is already live. Swift says so rather than opening a second session. */
        const val BUSY_REASON = "Busy"

        /** A currency code this build does not know: a caller bug, not a user one. */
        const val UNKNOWN_REASON = "Unknown"

        /**
         * Deliberately unvalidated, as the Reclaim credentials are: a build without a verifier
         * is one whose selfie row reports itself unavailable, not one that cannot run.
         */
        @Throws(Exception::class)
        fun create(
            account: AppleBaseAccount,
            apiUrl: String,
            apiKey: String,
            tenant: String,
            returnUrl: String,
            onUnrecognisedRevert: (String) -> Unit = {},
        ): AppleLivenessClient {
            val config = LivenessConfig(apiUrl = apiUrl, apiKey = apiKey, tenant = tenant)
            val reader = LivenessReader(rpc = account.rpc, network = account.network)
            return AppleLivenessClient(
                smartAccounts = account.smartAccounts,
                reader = reader,
                driver =
                    LivenessVerificationDriver(
                        widget =
                            LivenessWidgetClient(
                                httpClient = account.httpClient,
                                config = config,
                                redirectUri = returnUrl,
                            ),
                        reader = reader,
                        submitters = account.submitters,
                        rpc = account.rpc,
                        network = account.network,
                        config = config,
                        onUnrecognisedRevert = onUnrecognisedRevert,
                    ),
            )
        }
    }
}

/**
 * Admits one run at a time. [lock] is held for exactly as long as the collection lives, so
 * cancelling an abandoned run frees the next one; the same guard [singleRunFlow] gives Reclaim.
 */
internal fun singleLivenessRun(
    lock: Mutex,
    block: suspend FlowCollector<AppleLivenessStatus>.() -> Unit,
): Flow<AppleLivenessStatus> =
    flow {
        if (!lock.tryLock()) {
            emit(AppleLivenessStatus.Failed(AppleLivenessClient.BUSY_REASON))
            return@flow
        }
        try {
            block()
        } finally {
            lock.unlock()
        }
    }
