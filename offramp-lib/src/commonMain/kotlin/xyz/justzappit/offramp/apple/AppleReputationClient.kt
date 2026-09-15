// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.liveness.LivenessReader
import xyz.justzappit.offramp.liveness.LivenessStanding
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reclaim.ReclaimAppCredentials
import xyz.justzappit.offramp.reclaim.ReclaimLaunchSignal
import xyz.justzappit.offramp.reclaim.ReclaimPoller
import xyz.justzappit.offramp.reclaim.ReclaimSessionMinter
import xyz.justzappit.offramp.reclaim.ReclaimStatus
import xyz.justzappit.offramp.reclaim.ReclaimVerificationDriver
import xyz.justzappit.offramp.reputation.ReputationReader
import kotlin.time.Clock

/**
 * Swift-friendly facade over the reputation reads and the Reclaim verification driver.
 *
 * It exists because [AppleBaseAccount] keeps its rpc, submitters and http client `internal`:
 * Swift can name [ReputationReader] and [ReclaimVerificationDriver] but cannot construct either.
 *
 * The one behaviour here that is not a pass-through is [singleRunFlow]'s guard: one verification
 * at a time, whatever Swift asks for.
 */
class AppleReputationClient private constructor(
    private val smartAccounts: SmartOfframpAccountProvider,
    private val reader: ReputationReader,
    private val livenessReader: LivenessReader,
    private val driver: ReclaimVerificationDriver,
) {
    private val runLock = Mutex()

    /**
     * The live run's signal. A [MutableStateFlow] rather than a plain field because
     * [markVerifierOpened] is called from whatever thread Swift's `UIApplication.open` completes
     * on, and a call that cannot see the signal yet is a call that never stops the re-minting.
     */
    private val activeSignal = MutableStateFlow<ReclaimLaunchSignal?>(null)

    /**
     * Both standings in one pass, as Android's `ReputationVM.load` reads them: one failed read
     * fails the whole thing, never a summary beside a silently zeroed selfie limit.
     */
    @Throws(Exception::class)
    suspend fun summary(currencyCode: String): AppleReputationSummary {
        val address = smartAccounts.resolve().address
        val currency = CurrencyCode.fromCode(currencyCode)
        return coroutineScope {
            val reputation = async { reader.read(address, currency) }
            val standing = async { livenessReader.read(address) }
            reputation.await().toApple(standing.await(), livenessReader.isAvailable)
        }
    }

    fun verify(platformId: String, currencyCode: String): Flow<AppleReclaimStatus> =
        singleRunFlow(runLock, platformId, currencyCode) { platform, currency ->
            val signal = ReclaimLaunchSignal()
            activeSignal.value = signal
            try {
                driver.verify(platform, currency, signal).collect { emit(it.toAppleWithStanding()) }
            } finally {
                // Only ever clears this run's own signal, never one a later run has published.
                activeSignal.compareAndSet(signal, null)
            }
        }

    /** Continues the session named by the return link after iOS relaunched the app. */
    fun resume(platformId: String, currencyCode: String, sessionId: String): Flow<AppleReclaimStatus> =
        singleRunFlow(runLock, platformId, currencyCode) { platform, currency ->
            // Nothing to hold open: the user has already been and come back.
            activeSignal.value = null
            driver.resume(platform, currency, sessionId).collect { emit(it.toAppleWithStanding()) }
        }

    /**
     * `Done` carries the summary the chain reported back; the selfie standing is read beside it so
     * the screen the run lands on shows the same higher limit [summary] would. Only the confirming
     * read can fail here, and the write has landed, so a failed one crosses as no standing.
     */
    private suspend fun ReclaimStatus.toAppleWithStanding(): AppleReclaimStatus =
        if (this is ReclaimStatus.Done) {
            toApple(standingOrNull(smartAccounts.resolve().address), livenessReader.isAvailable)
        } else {
            toApple()
        }

    private suspend fun standingOrNull(address: Address): LivenessStanding? =
        try {
            livenessReader.read(address)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") ignored: Exception,
        ) {
            null
        }

    /**
     * Stops the re-minting. Call it once `UIApplication.open` has returned true, never on the tap;
     * `ReclaimVerificationDriver.mintAndHold` is where what each mistake costs is written down.
     *
     * Swift owns which run this belongs to. Ending a run is likewise Swift's: cancelling the
     * collection frees the lock and [verify]'s own `finally` forgets the signal.
     */
    fun markVerifierOpened() {
        activeSignal.value?.markLaunched()
    }

    companion object {
        /** A run is already live. Swift says so rather than minting a second session. */
        const val BUSY_REASON = "Busy"

        /** A platform id or currency code this build does not know: a caller bug, not a user one. */
        const val UNKNOWN_REASON = "Unknown"

        @Throws(Exception::class)
        suspend fun create(
            account: AppleBaseAccount,
            reclaimAppId: String,
            reclaimAppSecret: String,
            reclaimReturnUrl: String,
        ): AppleReputationClient {
            // Deliberately unvalidated. A build without working Reclaim credentials is a build
            // that cannot verify, not one that cannot run: the driver already emits NotConfigured.
            val credentials = ReclaimAppCredentials(appId = reclaimAppId, appSecret = reclaimAppSecret)
            val reader = ReputationReader(rpc = account.rpc, network = account.network)
            return AppleReputationClient(
                smartAccounts = account.smartAccounts,
                reader = reader,
                livenessReader = LivenessReader(rpc = account.rpc, network = account.network),
                driver =
                    ReclaimVerificationDriver(
                        minter =
                            ReclaimSessionMinter(
                                httpClient = account.httpClient,
                                credentials = credentials,
                                nowMillis = { Clock.System.now().toEpochMilliseconds() },
                                redirectUrl = reclaimReturnUrl,
                            ),
                        poller = ReclaimPoller(httpClient = account.httpClient),
                        submitters = account.submitters,
                        reputationReader = reader,
                        rpc = account.rpc,
                        network = account.network,
                        credentials = credentials,
                    ),
            )
        }
    }
}
