// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.identity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.offramp.account.SubmittingAccount
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.IdentityCheck
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
    private val resolveAccount: suspend () -> SubmittingAccount,
    private val store: IdentityVerificationStore,
    private val nowSeconds: () -> Long,
    rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    onUnrecognisedRevert: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val submission = IdentityAttestationSubmitter(rpc, network, reputationReader, store, onUnrecognisedRevert)

    fun isOffered(check: IdentityCheck, currency: CurrencyCode): Boolean =
        services.of(check) != null && check.isOfferedIn(currency)

    /** A persisted result is resumed on screen load, including a receipt whose confirming read failed. */
    suspend fun recoverableCheck(currency: CurrencyCode): IdentityCheck? =
        mutex.withLock {
            val account = resolveAccount()
            IdentityCheck.entries.firstOrNull { check ->
                isOffered(check, currency) &&
                    store.get(key(account, check))?.let { it.currency == currency && it.canRecover } == true
            }
        }

    /** Explicit cancellation invalidates a browser session, but never throws away a redeemed result. */
    suspend fun cancelWaiting(check: IdentityCheck, currency: CurrencyCode) {
        mutex.withLock {
            val key = key(resolveAccount(), check)
            val pending = store.get(key)
            if (pending?.currency == currency && !pending.canRecover) store.set(key, null)
        }
    }

    fun verify(
        check: IdentityCheck,
        currency: CurrencyCode,
        nonce: String,
        returnSignal: IdentityReturnSignal,
    ): Flow<IdentityStatus> =
        flow {
            mutex.withLock {
                val service = services.of(check)
                if (service == null || !check.isOfferedIn(currency)) {
                    emit(IdentityStatus.Failed(IdentityFailure.Unavailable))
                    return@withLock
                }
                emit(IdentityStatus.Preparing)
                val account = resolveAccount()
                val key = key(account, check)
                val previous = store.get(key)
                if (previous?.canRecover == true) {
                    // The signed result is wallet-wide; finish it even if the selected corridor changed.
                    complete(service, check, key, previous, currency, account)
                    return@withLock
                }
                val pending =
                    PendingIdentityVerification(
                        state = IdentityReturn.state(nonce, currency),
                        currency = currency,
                        expiresAtSeconds = nowSeconds() + SESSION_TTL_SECONDS,
                    )
                // Persist before the browser can receive a link. Failure to persist means no session opens.
                store.set(key, pending)
                val country = if (check == IdentityCheck.Passport) currency.passportCountry else null
                val widgetUrl =
                    try {
                        widget.createSession(service, account.address, returnUrl(check), pending.state, country)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IdentityException) {
                        emit(IdentityStatus.Failed(e.failure(whenRefused = IdentityFailure.Unavailable)))
                        return@withLock
                    } catch (ignored: Exception) {
                        emit(IdentityStatus.Failed(IdentityFailure.Network))
                        return@withLock
                    }
                emit(IdentityStatus.Ready(widgetUrl))
                emit(IdentityStatus.Verifying)
                acceptReturn(service, check, key, returnSignal.await(), currency, account)
            }
        }

    /** Cold starts use exactly the same session validation as live runs. */
    fun resume(ret: IdentityReturn, currency: CurrencyCode): Flow<IdentityStatus> =
        flow {
            mutex.withLock {
                val service = services.of(ret.check)
                if (service == null || !ret.check.isOfferedIn(currency)) {
                    emit(IdentityStatus.Failed(IdentityFailure.Unavailable))
                    return@withLock
                }
                val account = resolveAccount()
                acceptReturn(service, ret.check, key(account, ret.check), ret, currency, account)
            }
        }

    private suspend fun FlowCollector<IdentityStatus>.acceptReturn(
        service: IdentityService,
        check: IdentityCheck,
        key: String,
        ret: IdentityReturn,
        currency: CurrencyCode,
        account: SubmittingAccount,
    ) {
        val pending = store.get(key)
        val sessionMatches =
            pending != null && ret.check == check && ret.state == pending.state && pending.currency == currency
        // A consumed callback is never a retry command. Preserve its recovery record, but require
        // verify/recoverableCheck for recovery so replaying a URL cannot initiate a fresh send.
        val unconsumed = pending?.code == null
        if (!sessionMatches || nowSeconds() >= pending.expiresAtSeconds || !unconsumed) {
            emit(IdentityStatus.Failed(IdentityFailure.Rejected))
            return
        }
        if (ret.code.isNullOrBlank()) {
            if (!pending.canRecover) store.set(key, null)
            emit(IdentityStatus.Failed(widgetFailure(ret.error)))
            return
        }
        val accepted = pending.copy(code = ret.code)
        store.set(key, accepted)
        complete(service, check, key, accepted, currency, account)
    }

    @Suppress("ReturnCount")
    private suspend fun FlowCollector<IdentityStatus>.complete(
        service: IdentityService,
        check: IdentityCheck,
        key: String,
        initial: PendingIdentityVerification,
        currency: CurrencyCode,
        account: SubmittingAccount,
    ) {
        emit(IdentityStatus.Submitting)
        var pending = initial
        if (pending.receiptBlock == null && pending.transactionHash == null) {
            if (pending.attestation == null) {
                if (nowSeconds() >= pending.expiresAtSeconds) {
                    store.set(key, null)
                    emit(IdentityStatus.Failed(IdentityFailure.Expired))
                    return
                }
                pending = redeem(service, key, pending) ?: return
            }
            val attestation = requireNotNull(pending.attestation).decode()
            if (attestation.expiry <= BigInteger(nowSeconds().toString())) {
                store.set(key, null)
                emit(IdentityStatus.Failed(IdentityFailure.Expired))
                return
            }
        }
        val block =
            pending.receiptBlock ?: when (val result = submission.submit(account, check, key, pending, currency)) {
                is IdentitySubmission.Confirmed -> {
                    result.block
                }

                is IdentitySubmission.Verified -> {
                    emit(IdentityStatus.Done(result.summary))
                    return
                }

                is IdentitySubmission.Failed -> {
                    emit(IdentityStatus.Failed(result.reason))
                    return
                }
            }
        val summary =
            try {
                reputationReader.readAt(account.address, currency, block)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // Keep the receipt: retry only the read, never the attestation or transaction.
                emit(IdentityStatus.Failed(IdentityFailure.Network))
                return
            }
        store.set(key, null)
        emit(IdentityStatus.Done(summary))
    }

    private fun key(account: SubmittingAccount, check: IdentityCheck): String =
        "${network.chainId.value}_${network.reputationManagerAddress.lowercaseHex}_" +
            "${account.address.lowercaseHex}_${check.name}"

    /** The attestation for a one-time code, or null once the failure has been emitted. */
    private suspend fun FlowCollector<IdentityStatus>.redeem(
        service: IdentityService,
        key: String,
        pending: PendingIdentityVerification,
    ): PendingIdentityVerification? {
        val failure =
            try {
                // Once redemption starts, leaving the screen must not cancel the response-to-disk handoff.
                return withContext(NonCancellable) {
                    val attestation = widget.redeem(service, requireNotNull(pending.code))
                    pending.copy(attestation = StoredIdentityAttestation.from(attestation)).also { store.set(key, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IdentityException) {
                // A refused code is one the service no longer has: used, or past its TTL.
                e.failure(whenRefused = IdentityFailure.Expired)
            } catch (ignored: Exception) {
                IdentityFailure.Network
            }
        if (failure == IdentityFailure.Expired) store.set(key, null)
        emit(IdentityStatus.Failed(failure))
        return null
    }

    private fun widgetFailure(error: String?): IdentityFailure =
        when (error) {
            WIDGET_CANCELLED -> IdentityFailure.Cancelled
            WIDGET_DUPLICATE -> IdentityFailure.AlreadyClaimed
            WIDGET_EXPIRED -> IdentityFailure.Expired
            else -> IdentityFailure.NotPassed
        }

    private companion object {
        // Bound browser authorization even if a stale link survives the provider's own session TTL.
        const val SESSION_TTL_SECONDS = 30 * 60L

        const val WIDGET_CANCELLED = "cancelled"
        const val WIDGET_DUPLICATE = "duplicate_person"
        const val WIDGET_EXPIRED = "expired"
    }
}

private fun IdentityException.failure(whenRefused: IdentityFailure): IdentityFailure =
    if (isClientRejection) whenRefused else IdentityFailure.Network
