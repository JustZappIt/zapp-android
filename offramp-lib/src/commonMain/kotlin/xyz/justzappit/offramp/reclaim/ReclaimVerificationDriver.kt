// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reclaim

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.evm.abi.Selector4
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
     * A session is live and the user has ~10 minutes to use it. [requestUrl] resolves to the
     * Verifier app when it is installed; [installIntentUrl] is the store-then-resume fallback for
     * when nothing on the device handles the link.
     */
    data class Ready(
        val requestUrl: String,
        val installIntentUrl: String,
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
 * **The session is re-minted while the user waits to tap.** A session lives about ten minutes and
 * cannot be extended, and installing the Verifier and signing in can eat most of that — so a link
 * minted on screen entry is often dead by the time it is used. Re-minting stops the instant the
 * user leaves, because from then on the live session is the one being polled and replacing it
 * would abort a verification already in progress.
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
            val session = mintAndHold(platform, account.address, launchSignal) ?: return@flow

            emit(ReclaimStatus.Verifying)
            val proofs = awaitProofs(session.sessionId) ?: return@flow

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
        smartAccount: Address,
        launchSignal: ReclaimLaunchSignal,
    ): ReclaimSession? {
        // §5.2: minted for the smart account, because that is the msg.sender the contract sees.
        var session =
            try {
                minter.mint(platform, smartAccount)
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
                    minter.mint(platform, smartAccount)
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Exception) {
                    // The existing link may still be good; keep it rather than failing a
                    // verification the user has not even started.
                    null
                }
            if (reminted != null) {
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
            account.submitter.awaitReceipt(txHash)
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
            installIntentUrl = minter.installIntentUrl(session.requestUrl),
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

    private fun classify(e: Exception): ReclaimFailure {
        if (e is RpcException.ExecutionReverted) return classifyRevert(e)
        // The bundler reports an on-chain revert as text, so the selector arrives inside a message
        // rather than as structured data.
        val message = e.message.orEmpty()
        return when {
            message.contains(ADDRESS_MISMATCH, ignoreCase = true) -> {
                ReclaimFailure.AddressMismatch
            }

            REPLAY_SELECTOR.hex in message -> {
                ReclaimFailure.AlreadyVerifiedElsewhere
            }

            VERIFICATION_FAILED_SELECTOR.hex in message -> {
                ReclaimFailure.VerificationRejected
            }

            SPONSORSHIP_MARKERS.any { message.contains(it, ignoreCase = true) } -> {
                ReclaimFailure.SponsorshipUnavailable
            }

            else -> {
                ReclaimFailure.Network
            }
        }
    }

    private fun classifyRevert(e: RpcException.ExecutionReverted): ReclaimFailure =
        when {
            e.solidityErrorString?.contains(ADDRESS_MISMATCH, ignoreCase = true) == true -> {
                ReclaimFailure.AddressMismatch
            }

            e.selector == REPLAY_SELECTOR -> {
                ReclaimFailure.AlreadyVerifiedElsewhere
            }

            e.selector == VERIFICATION_FAILED_SELECTOR -> {
                ReclaimFailure.VerificationRejected
            }

            else -> {
                ReclaimFailure.VerificationRejected
            }
        }

    private companion object {
        /** Re-mint well inside the ~10-minute session life, and only before the user leaves. */
        const val DEFAULT_REMINT_INTERVAL_MS = 4 * 60 * 1_000L

        const val ADDRESS_MISMATCH = "User address mismatch"

        /** The Reclaim verifier's replay rejection: one social account, one wallet, forever. */
        val REPLAY_SELECTOR: Selector4 = Selector4.fromHex("0x2f850b6b")
        val VERIFICATION_FAILED_SELECTOR: Selector4 = Selector4.fromHex("0x439cc0cd")

        val SPONSORSHIP_MARKERS =
            listOf("paymaster", "sponsor", "AA31", "AA33", "prefund")
    }
}
