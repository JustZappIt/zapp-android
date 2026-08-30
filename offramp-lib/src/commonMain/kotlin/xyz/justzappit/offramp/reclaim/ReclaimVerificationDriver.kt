// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reclaim

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.offramp.account.Erc4337SubmitterProvider
import xyz.justzappit.offramp.account.SubmittingAccount
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.ReputationCalls
import xyz.justzappit.offramp.reputation.ReputationReader
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.SocialPlatform

sealed interface ReclaimStatus {
    data object Preparing : ReclaimStatus

    /**
     * A session is live and the user has ~10 minutes to use it.
     *
     * Three links, tried in order, because each fails on a device the next one handles:
     * [requestUrl] resolves to the Verifier app when it is installed and to a browser otherwise;
     * [installIntentUrl] is the Play deep link for a device with neither; [storeUrl] is the same
     * store page over https, which is all a `foss` build on a de-Googled phone can open.
     */
    data class Ready(
        val requestUrl: String,
        val installIntentUrl: String,
        val storeUrl: String,
    ) : ReclaimStatus

    data object Verifying : ReclaimStatus

    data object Submitting : ReclaimStatus

    data class Done(
        val summary: ReputationSummary
    ) : ReclaimStatus

    data class Failed(
        val reason: ReclaimFailure
    ) : ReclaimStatus
}

/** Each of these needs its own sentence to the user; none of them is "something went wrong". */
enum class ReclaimFailure {
    /** No Reclaim credentials in this build. A misconfiguration, not a user problem. */
    NotConfigured,

    /** The provider's own age rule. The account is real, it is just too new. */
    CriteriaNotMet,

    ProofGenerationFailed,

    /** The session expired, or the user came back far too late. Retrying mints a fresh one. */
    SessionExpired,

    /** That social account is already verified onto another wallet. Permanent, by design. */
    AlreadyVerifiedElsewhere,

    /** §5.2 was violated: the session was minted for an address that is not the submitter. */
    AddressMismatch,

    /** The Reclaim verifier contract rejected the proof itself. */
    VerificationRejected,

    /** Pimlico would not sponsor. Never fall through to asking the user for ETH. */
    SponsorshipUnavailable,

    Network,
}

/** Single-shot signal that the user has actually left for the Verifier app. */
class ReclaimLaunchSignal {
    private val launched = CompletableDeferred<Unit>()

    val isLaunched: Boolean get() = launched.isCompleted

    fun markLaunched() {
        launched.complete(Unit)
    }

    internal suspend fun await() {
        launched.await()
    }
}

/**
 * One verification, start to finish: mint a session, hand the user to the Reclaim app, wait for
 * the attestors, then write the proof to the ReputationManager from the user's own smart account.
 *
 * Two things here are less obvious than they look.
 *
 * **The session is re-minted while the user waits to tap**, and stops the instant they leave. Why,
 * and what goes wrong either way, is on [mintAndHold].
 *
 * **Every send is simulated first.** `eth_call` with the smart account as `from` runs the real
 * proof against the real contract for nothing, which turns "User address mismatch" from a revert
 * the user waits on a bundler to discover into a message shown before any of that. Kept
 * permanently rather than only for the first run.
 */
class ReclaimVerificationDriver(
    private val minter: ReclaimSessionMinter,
    private val poller: ReclaimPoller,
    private val submitters: Erc4337SubmitterProvider,
    private val reputationReader: ReputationReader,
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val credentials: ReclaimAppCredentials,
    private val remintIntervalMillis: Long = DEFAULT_REMINT_INTERVAL_MS,
    /**
     * Called with the selector of a revert this build has no mapping for. Every unmapped selector
     * reaches the user as the same generic "couldn't verify that proof", so without this the
     * difference between a real VerificationFailed and a brand-new contract error is invisible in
     * a log — which is how `UserIdAlreadyVerified` went undiagnosed.
     */
    private val onUnrecognisedRevert: (String) -> Unit = {},
) {
    fun verify(
        platform: SocialPlatform,
        currency: CurrencyCode,
        launchSignal: ReclaimLaunchSignal,
    ): Flow<ReclaimStatus> =
        flow {
            if (!credentials.isConfigured) {
                emit(ReclaimStatus.Failed(ReclaimFailure.NotConfigured))
                return@flow
            }
            emit(ReclaimStatus.Preparing)

            val account = submitters.resolve()
            val session = mintAndHold(platform, currency, account.address, launchSignal) ?: return@flow

            emit(ReclaimStatus.Verifying)
            val proofs = awaitProofs(session.sessionId) ?: return@flow

            emit(ReclaimStatus.Submitting)
            submit(platform, currency, account, proofs)
        }

    /** Continues the session named by the return link after Android recreated the app process. */
    fun resume(
        platform: SocialPlatform,
        currency: CurrencyCode,
        sessionId: String,
    ): Flow<ReclaimStatus> =
        flow {
            if (sessionId.isBlank()) {
                emit(ReclaimStatus.Failed(ReclaimFailure.SessionExpired))
                return@flow
            }
            emit(ReclaimStatus.Verifying)
            val account = submitters.resolve()
            val proofs = awaitProofs(sessionId) ?: return@flow

            emit(ReclaimStatus.Submitting)
            submit(platform, currency, account, proofs)
        }

    /**
     * Mint a session, then keep it alive until the user actually leaves for the Verifier.
     *
     * A session lives about ten minutes and cannot be extended, and installing the Verifier and
     * signing in can eat most of that — a link minted on screen entry is often dead by the time it
     * is tapped. Re-minting stops the instant the user leaves: from then on the live session is
     * the one being polled, and replacing it would abort a verification already under way.
     */
    private suspend fun FlowCollector<ReclaimStatus>.mintAndHold(
        platform: SocialPlatform,
        currency: CurrencyCode,
        smartAccount: Address,
        launchSignal: ReclaimLaunchSignal,
    ): ReclaimSession? {
        // §5.2: minted for the smart account, because that is the msg.sender the contract sees.
        var session =
            try {
                minter.mint(platform, smartAccount, currency)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                emit(ReclaimStatus.Failed(ReclaimFailure.Network))
                return null
            }
        emit(ready(session))

        while (withTimeoutOrNull(remintIntervalMillis) { launchSignal.await() } == null) {
            val reminted =
                try {
                    minter.mint(platform, smartAccount, currency)
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Exception) {
                    // The existing link may still be good; keep it rather than failing a
                    // verification the user has not even started.
                    null
                }
            // ☠ The loop only tests the signal at the top, so a tap that lands while the mint
            // above is in flight is invisible here. Adopting the fresh session then would leave
            // the user verifying against the link they actually opened while `awaitProofs` polls
            // a session nobody ever touched — ten minutes of "Waiting for Reclaim…" ending in a
            // bogus "session expired" on a verification that in fact succeeded.
            if (reminted != null && !launchSignal.isLaunched) {
                session = reminted
                emit(ready(session))
            }
        }
        return session
    }

    private suspend fun FlowCollector<ReclaimStatus>.awaitProofs(sessionId: String): List<ReclaimSessionProof>? =
        when (val outcome = poller.await(sessionId)) {
            is ReclaimPollResult.Proofs -> {
                outcome.proofs
            }

            ReclaimPollResult.CriteriaNotMet -> {
                emit(ReclaimStatus.Failed(ReclaimFailure.CriteriaNotMet))
                null
            }

            ReclaimPollResult.GenerationFailed -> {
                emit(ReclaimStatus.Failed(ReclaimFailure.ProofGenerationFailed))
                null
            }

            ReclaimPollResult.SessionGone, ReclaimPollResult.TimedOut -> {
                emit(ReclaimStatus.Failed(ReclaimFailure.SessionExpired))
                null
            }
        }

    /** Encode, simulate, send, then re-read what the chain now says about this account. */
    @Suppress("ReturnCount")
    private suspend fun FlowCollector<ReclaimStatus>.submit(
        platform: SocialPlatform,
        currency: CurrencyCode,
        account: SubmittingAccount,
        proofs: List<ReclaimSessionProof>,
    ) {
        val calldata =
            try {
                ReputationCalls.socialVerifyCalldata(platform, proofs.map(ReclaimProofTransform::toOnChain))
            } catch (ignored: IllegalArgumentException) {
                // A proof we cannot encode is a proof the chain would reject anyway.
                emit(ReclaimStatus.Failed(ReclaimFailure.VerificationRejected))
                return
            }

        simulationFailure(account.address, calldata)?.let {
            emit(ReclaimStatus.Failed(it))
            return
        }

        try {
            val txHash =
                account.submitter.sendTransaction(
                    to = network.reputationManagerAddress,
                    value = Wei.ZERO,
                    data = calldata,
                )
            // Inclusion is not success. A sponsored operation that reverts still gets mined and
            // still returns a receipt, and without this the user reaches the "verified" screen with
            // reputation that never changed — and no reason on screen for why the limit did not move.
            if (!account.submitter.awaitReceipt(txHash).success) {
                emit(ReclaimStatus.Failed(ReclaimFailure.VerificationRejected))
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            // Any failure between here and the receipt has to become a sentence for the user, so
            // the catch is broad on purpose and [classify] does the narrowing.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            emit(ReclaimStatus.Failed(classify(e)))
            return
        }

        val summary =
            try {
                reputationReader.read(account.address, currency)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // The write landed; only the confirming read failed. The screen this returns to
                // re-reads on appearance, so it recovers on its own.
                emit(ReclaimStatus.Failed(ReclaimFailure.Network))
                return
            }
        emit(ReclaimStatus.Done(summary))
    }

    private fun ready(session: ReclaimSession) =
        ReclaimStatus.Ready(
            requestUrl = session.requestUrl,
            installIntentUrl = minter.installIntentUrl(),
            storeUrl = ReclaimSessionMinter.VERIFIER_STORE_URL,
        )

    /** Null when the call would succeed. Costs nothing and runs before every send. */
    private suspend fun simulationFailure(from: Address, calldata: ByteArray): ReclaimFailure? =
        try {
            rpc.ethCall(to = network.reputationManagerAddress, data = calldata, from = from)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            // A simulation that cannot run is not a proof that the send would fail: let the send
            // decide rather than refusing on a dropped request.
            (e as? RpcException.ExecutionReverted)?.let(::classifyRevert)
        }

    @Suppress("ReturnCount")
    private fun classify(e: Exception): ReclaimFailure {
        if (e is RpcException.ExecutionReverted) return classifyRevert(e)
        // The bundler reports an on-chain revert as text, so the selector arrives inside a message
        // rather than as structured data.
        val message = e.message.orEmpty()
        if (message.contains(ADDRESS_MISMATCH, ignoreCase = true)) return ReclaimFailure.AddressMismatch
        REVERTS.entries.firstOrNull { it.key in message }?.let { return it.value }
        if (SPONSORSHIP_MARKERS.any { message.contains(it, ignoreCase = true) }) {
            return ReclaimFailure.SponsorshipUnavailable
        }
        return ReclaimFailure.Network
    }

    private fun classifyRevert(e: RpcException.ExecutionReverted): ReclaimFailure {
        val selector = e.selector?.hex
        val mapped = selector?.let(REVERTS::get)
        return when {
            e.solidityErrorString?.contains(ADDRESS_MISMATCH, ignoreCase = true) == true -> {
                ReclaimFailure.AddressMismatch
            }

            mapped != null -> {
                mapped
            }

            else -> {
                onUnrecognisedRevert(selector ?: e.solidityErrorString ?: "revert with no data")
                ReclaimFailure.VerificationRejected
            }
        }
    }

    private companion object {
        /** Re-mint well inside the ~10-minute session life, and only before the user leaves. */
        const val DEFAULT_REMINT_INTERVAL_MS = 4 * 60 * 1_000L

        const val ADDRESS_MISMATCH = "User address mismatch"

        /**
         * The RpHelper's `Errors.sol` selectors, transcribed from p2p.me's own client
         * (`user-app-client/src/lib/errors.ts`) rather than guessed. Keyed by hex because the
         * bundler hands the same value back as text inside a message.
         *
         * ☠ Three different errors mean "one account, one wallet", and the contract picks between
         * them by *which field* collided — the social handle, the provider's user id, or the
         * nullifier. All three are the same sentence to the user, and mapping only the first is
         * what made a already-used Facebook login read as "the exchange couldn't verify that proof".
         */
        val REVERTS: Map<String, ReclaimFailure> =
            mapOf(
                // One social account, one wallet, forever.
                "0x2f850b6b" to ReclaimFailure.AlreadyVerifiedElsewhere, // SocialAlreadyVerified
                "0xa18ea4e8" to ReclaimFailure.AlreadyVerifiedElsewhere, // UserIdAlreadyVerified
                "0x69470b13" to ReclaimFailure.AlreadyVerifiedElsewhere, // UsernameAlreadyVerified
                "0x0f165e7b" to ReclaimFailure.AlreadyVerifiedElsewhere, // NullifierAlreadyVerified
                // The proof came back without the field the contract reads. Retrying can fix it;
                // picking a different account usually does.
                "0x4d460588" to ReclaimFailure.ProofGenerationFailed, // UserIdFieldNotInProof
                "0x8390b2dd" to ReclaimFailure.ProofGenerationFailed, // UsernameNotInProof
                // No year in the proof is how the age rule actually surfaces on chain.
                "0x466f52a8" to ReclaimFailure.CriteriaNotMet, // YearFieldNotInProof
                "0x2366073b" to ReclaimFailure.VerificationRejected, // InvalidSocialPlatform
                "0x439cc0cd" to ReclaimFailure.VerificationRejected, // VerificationFailed
            )

        val SPONSORSHIP_MARKERS =
            listOf("paymaster", "sponsor", "AA31", "AA33", "prefund")
    }
}
