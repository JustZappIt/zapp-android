// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.liveness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.offramp.account.Erc4337SubmitterProvider
import xyz.justzappit.offramp.account.SubmittingAccount
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.p2p.CurrencyCode

sealed interface LivenessStatus {
    data object Preparing : LivenessStatus

    /** A widget session is open; the user has [expiresInSeconds] to finish it. */
    data class Ready(
        val widgetUrl: String,
        val expiresInSeconds: Int,
    ) : LivenessStatus

    data object Verifying : LivenessStatus

    data object Submitting : LivenessStatus

    data class Done(
        val standing: LivenessStanding
    ) : LivenessStatus

    data class Failed(
        val reason: LivenessFailure
    ) : LivenessStatus
}

enum class LivenessFailure {
    /** No verifier in this build, or no integrator on this network. */
    NotConfigured,

    /** The selfie did not pass. Retrying with better light usually does. */
    NotLive,

    /** This face has already claimed on another wallet. Permanent, by design. */
    AlreadyClaimed,

    /** The widget session or the attestation ran out before it was used. */
    Expired,

    /** The user backed out of the widget. Not an error to show. */
    Cancelled,

    /** The service or the contract refused: a wrong attestor, a blocked wallet, a paused ramp. */
    Rejected,

    /** Pimlico would not sponsor. Never fall through to asking the user for ETH. */
    SponsorshipUnavailable,

    Network,
}

/** Delivers the widget's redirect to the run that is waiting on it. */
class LivenessReturnSignal {
    private val result = CompletableDeferred<LivenessReturn>()

    fun deliver(ret: LivenessReturn) {
        result.complete(ret)
    }

    internal suspend fun await(): LivenessReturn = result.await()
}

/**
 * One verification, start to finish: open a widget session for the smart account, hand the user
 * to it, wait for the redirect, redeem the code, then submit the attestation from the smart
 * account so `msg.sender` is the wallet the attestation names.
 *
 * Every send is simulated first, so a spent nullifier or a wrong attestor is a sentence before
 * any bundler is involved.
 */
class LivenessVerificationDriver(
    private val widget: LivenessWidgetClient,
    private val reader: LivenessReader,
    private val submitters: Erc4337SubmitterProvider,
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val config: LivenessConfig,
    private val onUnrecognisedRevert: (String) -> Unit = {},
) {
    fun verify(
        currency: CurrencyCode,
        nonce: String,
        returnSignal: LivenessReturnSignal,
    ): Flow<LivenessStatus> =
        flow {
            val integrator = network.livenessIntegratorAddress
            if (!config.isConfigured || integrator == null) {
                emit(LivenessStatus.Failed(LivenessFailure.NotConfigured))
                return@flow
            }
            emit(LivenessStatus.Preparing)

            val account = submitters.resolve()
            val state = LivenessReturn.state(nonce, currency)
            val session =
                try {
                    widget.createSession(account.address, state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LivenessException) {
                    emit(LivenessStatus.Failed(e.failure(whenRefused = LivenessFailure.Rejected)))
                    return@flow
                } catch (ignored: Exception) {
                    emit(LivenessStatus.Failed(LivenessFailure.Network))
                    return@flow
                }
            emit(LivenessStatus.Ready(session.widgetUrl, session.expiresInSeconds))
            emit(LivenessStatus.Verifying)

            val ret = returnSignal.await()
            // The widget echoes state only beside a code; an error return carries none, and is
            // taken at its word.
            if (ret.code != null && ret.state != state) {
                emit(LivenessStatus.Failed(LivenessFailure.Rejected))
                return@flow
            }
            complete(ret, account, integrator)
        }

    /** Finishes a run whose redirect arrived after Android recreated the process. */
    fun resume(ret: LivenessReturn): Flow<LivenessStatus> =
        flow {
            val integrator = network.livenessIntegratorAddress
            if (!config.isConfigured || integrator == null) {
                emit(LivenessStatus.Failed(LivenessFailure.NotConfigured))
                return@flow
            }
            emit(LivenessStatus.Verifying)
            complete(ret, submitters.resolve(), integrator)
        }

    @Suppress("ReturnCount")
    private suspend fun FlowCollector<LivenessStatus>.complete(
        ret: LivenessReturn,
        account: SubmittingAccount,
        integrator: Address,
    ) {
        val code = ret.code
        if (code.isNullOrBlank()) {
            emit(LivenessStatus.Failed(widgetFailure(ret.error)))
            return
        }
        emit(LivenessStatus.Submitting)

        val attestation = redeem(code) ?: return
        // The service signed for the wallet it was told; a mismatch here is our bug, not the user's.
        if (attestation.wallet != account.address) {
            emit(LivenessStatus.Failed(LivenessFailure.Rejected))
            return
        }
        val block = submit(account, integrator, LivenessCalls.submitAttestationCalldata(attestation)) ?: return
        val standing = readStanding(account.address, block) ?: return
        emit(LivenessStatus.Done(standing))
    }

    /** The attestation for a one-time code, or null once the failure has been emitted. */
    private suspend fun FlowCollector<LivenessStatus>.redeem(code: String): LivenessAttestation? {
        var failure: LivenessFailure? = null
        val attestation =
            try {
                widget.redeem(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LivenessException) {
                // A refused code is one the service no longer has: used, or past its TTL.
                failure = e.failure(whenRefused = LivenessFailure.Expired)
                null
            } catch (ignored: Exception) {
                failure = LivenessFailure.Network
                null
            }
        // No attestation and no failure: the service approved but its tenant is bound to no contract.
        if (attestation == null) emit(LivenessStatus.Failed(failure ?: LivenessFailure.NotConfigured))
        return attestation
    }

    /**
     * Simulate, send, wait for the receipt. The block the receipt names, or null once the failure
     * has been emitted.
     */
    private suspend fun FlowCollector<LivenessStatus>.submit(
        account: SubmittingAccount,
        integrator: Address,
        calldata: ByteArray,
    ): String? {
        simulationFailure(integrator, account.address, calldata)?.let {
            emit(LivenessStatus.Failed(it))
            return null
        }
        return try {
            val txHash = account.submitter.sendTransaction(to = integrator, value = Wei.ZERO, data = calldata)
            val receipt = account.submitter.awaitReceipt(txHash)
            if (receipt.success) {
                receipt.blockNumber
            } else {
                emit(LivenessStatus.Failed(LivenessFailure.Rejected))
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            // Anything between here and the receipt has to become a sentence for the user, so
            // the catch is broad on purpose and [classify] does the narrowing.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            emit(LivenessStatus.Failed(classify(e)))
            null
        }
    }

    /** Read at the attestation's own block: a node behind it would report the wallet unverified. */
    private suspend fun FlowCollector<LivenessStatus>.readStanding(
        wallet: Address,
        blockNumber: String,
    ): LivenessStanding? {
        val standing =
            try {
                reader.readAt(wallet, blockNumber)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                null
            }
        // The write landed; only the confirming read failed. The screen this returns to re-reads
        // on its next load, so it recovers on its own.
        if (standing == null) emit(LivenessStatus.Failed(LivenessFailure.Network))
        return standing
    }

    private fun widgetFailure(error: String?): LivenessFailure =
        when (error) {
            WIDGET_CANCELLED -> LivenessFailure.Cancelled
            WIDGET_DUPLICATE -> LivenessFailure.AlreadyClaimed
            WIDGET_EXPIRED -> LivenessFailure.Expired
            else -> LivenessFailure.NotLive
        }

    private suspend fun simulationFailure(
        integrator: Address,
        from: Address,
        calldata: ByteArray,
    ): LivenessFailure? =
        try {
            rpc.ethCall(to = integrator, data = calldata, from = from)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            (e as? RpcException.ExecutionReverted)?.let(::classifyRevert)
        }

    @Suppress("ReturnCount")
    private fun classify(e: Exception): LivenessFailure {
        if (e is RpcException.ExecutionReverted) return classifyRevert(e)
        val message = e.message.orEmpty()
        REVERTS.entries.firstOrNull { it.key in message }?.let { return it.value }
        if (SPONSORSHIP_MARKERS.any { message.contains(it, ignoreCase = true) }) {
            return LivenessFailure.SponsorshipUnavailable
        }
        return LivenessFailure.Network
    }

    private fun classifyRevert(e: RpcException.ExecutionReverted): LivenessFailure {
        val selector = e.selector?.hex
        return REVERTS[selector] ?: run {
            onUnrecognisedRevert(selector ?: e.solidityErrorString ?: "revert with no data")
            LivenessFailure.Rejected
        }
    }

    private companion object {
        const val WIDGET_CANCELLED = "cancelled"
        const val WIDGET_DUPLICATE = "duplicate_person"
        const val WIDGET_EXPIRED = "expired"

        /** `ZappCheckoutIntegrator`'s own errors, by selector; the bundler quotes them as text. */
        val REVERTS: Map<String, LivenessFailure> =
            mapOf(
                "0xb115d857" to LivenessFailure.AlreadyClaimed, // NullifierAlreadySpent
                "0x716dcc39" to LivenessFailure.Expired, // AttestationExpired
                "0x8baa579f" to LivenessFailure.Rejected, // InvalidSignature
                "0x56993a6d" to LivenessFailure.Rejected, // AttestorNotSet
                "0xc000e8e5" to LivenessFailure.Rejected, // UserIsBlocked
                "0xab35696f" to LivenessFailure.Rejected, // ContractPaused
            )

        val SPONSORSHIP_MARKERS = listOf("paymaster", "sponsor", "AA31", "AA33", "prefund")
    }
}

private fun LivenessException.failure(whenRefused: LivenessFailure): LivenessFailure =
    if (isClientRejection) whenRefused else LivenessFailure.Network
