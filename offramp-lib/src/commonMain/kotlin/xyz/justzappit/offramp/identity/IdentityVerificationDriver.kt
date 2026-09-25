// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.identity

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
import xyz.justzappit.offramp.reputation.IdentityCheck
import xyz.justzappit.offramp.reputation.ReputationCalls
import xyz.justzappit.offramp.reputation.ReputationReader
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.passportCountry

sealed interface IdentityStatus {
    data object Preparing : IdentityStatus

    data class Ready(
        val widgetUrl: String,
    ) : IdentityStatus

    data object Verifying : IdentityStatus

    data object Submitting : IdentityStatus

    data class Done(
        val summary: ReputationSummary,
    ) : IdentityStatus

    data class Failed(
        val reason: IdentityFailure,
    ) : IdentityStatus
}

enum class IdentityFailure {
    /** Not offered on this network or corridor, or the service would not open a session for us. */
    Unavailable,

    /** The widget did not pass the user. Retrying with better light usually does. */
    NotPassed,

    /** This face or document has already verified another wallet. Permanent, by design. */
    AlreadyClaimed,

    /** This wallet has already completed the check. */
    AlreadyVerified,

    /** The widget session, its one-time code or the attestation ran out before it was used. */
    Expired,

    /** The user backed out of the widget. Not an error to show. */
    Cancelled,

    /** The service or the contract refused the attestation. */
    Rejected,

    /** Pimlico would not sponsor. Never fall through to asking the user for ETH. */
    SponsorshipUnavailable,

    Network,
}

/** Delivers the widget's redirect to the run that is waiting on it. */
class IdentityReturnSignal {
    private val result = CompletableDeferred<IdentityReturn>()

    fun deliver(ret: IdentityReturn) {
        result.complete(ret)
    }

    internal suspend fun await(): IdentityReturn = result.await()
}

/**
 * One check, start to finish: open a widget session for the smart account, hand the user to it,
 * wait for the redirect, redeem the code, then submit the attestation to the ReputationManager
 * from the smart account, simulated first.
 */
class IdentityVerificationDriver(
    private val widget: IdentityWidgetClient,
    private val services: IdentityServices,
    private val returnUrl: (IdentityCheck) -> String,
    private val reputationReader: ReputationReader,
    private val submitters: Erc4337SubmitterProvider,
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val onUnrecognisedRevert: (String) -> Unit = {},
) {
    fun isOffered(check: IdentityCheck, currency: CurrencyCode): Boolean =
        services.of(check) != null && check.isOfferedIn(currency)

    fun verify(
        check: IdentityCheck,
        currency: CurrencyCode,
        nonce: String,
        returnSignal: IdentityReturnSignal,
    ): Flow<IdentityStatus> =
        flow {
            val service = services.of(check)
            if (service == null || !check.isOfferedIn(currency)) {
                emit(IdentityStatus.Failed(IdentityFailure.Unavailable))
                return@flow
            }
            emit(IdentityStatus.Preparing)

            val account = submitters.resolve()
            val state = IdentityReturn.state(nonce, currency)
            val country = if (check == IdentityCheck.Passport) currency.passportCountry else null
            val widgetUrl =
                try {
                    widget.createSession(service, account.address, returnUrl(check), state, country)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IdentityException) {
                    emit(IdentityStatus.Failed(e.failure(whenRefused = IdentityFailure.Unavailable)))
                    return@flow
                } catch (ignored: Exception) {
                    emit(IdentityStatus.Failed(IdentityFailure.Network))
                    return@flow
                }
            emit(IdentityStatus.Ready(widgetUrl))
            emit(IdentityStatus.Verifying)

            val ret = returnSignal.await()
            // The widget echoes state beside a code; an error return may carry none.
            if (ret.check != check || (ret.code != null && ret.state != state)) {
                emit(IdentityStatus.Failed(IdentityFailure.Rejected))
                return@flow
            }
            complete(service, ret, currency, account)
        }

    /** Finishes a check whose redirect arrived after the run that started it was gone. */
    fun resume(ret: IdentityReturn, currency: CurrencyCode): Flow<IdentityStatus> =
        flow {
            val service = services.of(ret.check)
            if (service == null) {
                emit(IdentityStatus.Failed(IdentityFailure.Unavailable))
                return@flow
            }
            emit(IdentityStatus.Verifying)
            complete(service, ret, currency, submitters.resolve())
        }

    @Suppress("ReturnCount")
    private suspend fun FlowCollector<IdentityStatus>.complete(
        service: IdentityService,
        ret: IdentityReturn,
        currency: CurrencyCode,
        account: SubmittingAccount,
    ) {
        val code = ret.code
        if (code.isNullOrBlank()) {
            emit(IdentityStatus.Failed(widgetFailure(ret.error)))
            return
        }
        emit(IdentityStatus.Submitting)

        val attestation = redeem(service, code) ?: return
        val calldata = ReputationCalls.submitIdentityAttestationCalldata(ret.check, attestation)
        val block = submit(account, calldata) ?: return
        val summary =
            try {
                reputationReader.readAt(account.address, currency, block)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // The write landed; only the confirming read failed, and the screen re-reads on load.
                emit(IdentityStatus.Failed(IdentityFailure.Network))
                return
            }
        emit(IdentityStatus.Done(summary))
    }

    /** The attestation for a one-time code, or null once the failure has been emitted. */
    private suspend fun FlowCollector<IdentityStatus>.redeem(
        service: IdentityService,
        code: String,
    ): IdentityAttestation? {
        val failure =
            try {
                return widget.redeem(service, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IdentityException) {
                // A refused code is one the service no longer has: used, or past its TTL.
                e.failure(whenRefused = IdentityFailure.Expired)
            } catch (ignored: Exception) {
                IdentityFailure.Network
            }
        emit(IdentityStatus.Failed(failure))
        return null
    }

    /** Simulate, send, wait for the receipt. The block it names, or null once the failure has been emitted. */
    private suspend fun FlowCollector<IdentityStatus>.submit(
        account: SubmittingAccount,
        calldata: ByteArray,
    ): String? {
        val to = network.reputationManagerAddress
        simulationFailure(to, account.address, calldata)?.let {
            emit(IdentityStatus.Failed(it))
            return null
        }
        return try {
            val txHash = account.submitter.sendTransaction(to = to, value = Wei.ZERO, data = calldata)
            val receipt = account.submitter.awaitReceipt(txHash)
            if (receipt.success) {
                receipt.blockNumber
            } else {
                emit(IdentityStatus.Failed(IdentityFailure.Rejected))
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            // Anything between here and the receipt has to become a sentence for the user, so the
            // catch is broad on purpose and [classify] does the narrowing.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            emit(IdentityStatus.Failed(classify(e)))
            null
        }
    }

    private fun widgetFailure(error: String?): IdentityFailure =
        when (error) {
            WIDGET_CANCELLED -> IdentityFailure.Cancelled
            WIDGET_DUPLICATE -> IdentityFailure.AlreadyClaimed
            WIDGET_EXPIRED -> IdentityFailure.Expired
            else -> IdentityFailure.NotPassed
        }

    private suspend fun simulationFailure(
        to: Address,
        from: Address,
        calldata: ByteArray,
    ): IdentityFailure? =
        try {
            rpc.ethCall(to = to, data = calldata, from = from)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            // A simulation that cannot run is not proof the send would fail; let the send decide.
            (e as? RpcException.ExecutionReverted)?.let(::classifyRevert)
        }

    @Suppress("ReturnCount")
    private fun classify(e: Exception): IdentityFailure {
        if (e is RpcException.ExecutionReverted) return classifyRevert(e)
        val message = e.message.orEmpty()
        REVERTS.entries.firstOrNull { it.key in message }?.let { return it.value }
        if (SPONSORSHIP_MARKERS.any { message.contains(it, ignoreCase = true) }) {
            return IdentityFailure.SponsorshipUnavailable
        }
        return IdentityFailure.Network
    }

    private fun classifyRevert(e: RpcException.ExecutionReverted): IdentityFailure {
        val selector = e.selector?.hex
        return selector?.let(REVERTS::get) ?: run {
            onUnrecognisedRevert(selector ?: e.solidityErrorString ?: "revert with no data")
            IdentityFailure.Rejected
        }
    }

    private companion object {
        const val WIDGET_CANCELLED = "cancelled"
        const val WIDGET_DUPLICATE = "duplicate_person"
        const val WIDGET_EXPIRED = "expired"

        /** The ReputationManager's errors for both checks, by selector; the bundler quotes them as text. */
        val REVERTS: Map<String, IdentityFailure> =
            mapOf(
                "0x66790623" to IdentityFailure.Unavailable, // LivenessSignerNotSet
                "0x54710b53" to IdentityFailure.Expired, // LivenessAttestationExpired
                "0xfa82e304" to IdentityFailure.Rejected, // LivenessNullifierZero
                "0x61746f27" to IdentityFailure.AlreadyClaimed, // LivenessNullifierAlreadySpent
                "0x95182781" to IdentityFailure.AlreadyVerified, // LivenessAlreadyVerified
                "0x9cfb0729" to IdentityFailure.Rejected, // LivenessInvalidSignature
                "0x2477577f" to IdentityFailure.Unavailable, // KycSignerNotSet
                "0xeb1a0215" to IdentityFailure.Expired, // KycAttestationExpired
                "0x311fde50" to IdentityFailure.Rejected, // KycNullifierZero
                "0xbdbb1a03" to IdentityFailure.AlreadyClaimed, // KycNullifierAlreadySpent
                "0x10afbce2" to IdentityFailure.AlreadyVerified, // KycAlreadyVerified
                "0x80686e11" to IdentityFailure.Rejected, // KycInvalidSignature
            )

        val SPONSORSHIP_MARKERS = listOf("paymaster", "sponsor", "AA31", "AA33", "prefund")
    }
}

private fun IdentityException.failure(whenRefused: IdentityFailure): IdentityFailure =
    if (isClientRejection) whenRefused else IdentityFailure.Network
