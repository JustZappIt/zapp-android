// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 The Zapp Contributors
package xyz.justzappit.offramp.apple

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import xyz.justzappit.offramp.identity.IdentityReturn
import xyz.justzappit.offramp.identity.IdentityReturnSignal
import xyz.justzappit.offramp.identity.IdentityServices
import xyz.justzappit.offramp.identity.IdentityStatus
import xyz.justzappit.offramp.identity.IdentityVerificationDriver
import xyz.justzappit.offramp.identity.IdentityVerificationStore
import xyz.justzappit.offramp.identity.IdentityWidgetClient
import xyz.justzappit.offramp.identity.PendingIdentityVerification
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.IdentityCheck
import xyz.justzappit.offramp.reputation.ReputationReader
import kotlin.time.Clock

/** Each key binds chain, contract, wallet and check. Host writes must be durable or throw. */
interface AppleIdentityStorage {
    @Throws(Exception::class)
    fun identityRecord(key: String): AppleStorageValue

    @Throws(Exception::class)
    fun storeIdentityRecord(key: String, value: String?)
}

internal class AppleIdentityStore(
    private val storage: AppleIdentityStorage
) : IdentityVerificationStore {
    override suspend fun get(key: String): PendingIdentityVerification? =
        storage.identityRecord(key).value?.let { Json.decodeFromString<PendingIdentityVerification>(it) }

    override suspend fun set(key: String, pending: PendingIdentityVerification?) {
        storage.storeIdentityRecord(key, pending?.let { Json.encodeToString(PendingIdentityVerification.serializer(), it) })
    }
}

sealed class AppleIdentityStatus {
    data object Preparing : AppleIdentityStatus()

    data class Ready(
        val widgetUrl: String
    ) : AppleIdentityStatus()

    data object Verifying : AppleIdentityStatus()

    data object Submitting : AppleIdentityStatus()

    data class Done(
        val summary: AppleReputationSummary
    ) : AppleIdentityStatus()

    data class Failed(
        val reason: String
    ) : AppleIdentityStatus()
}

/** A single shared driver owns validation and recovery for live and cold-start returns. */
class AppleIdentityClient private constructor(
    private val driver: IdentityVerificationDriver
) {
    private val runLock = Mutex()

    private data class Active(
        val check: IdentityCheck,
        val state: String,
        val signal: IdentityReturnSignal
    )

    private val active = MutableStateFlow<Active?>(null)

    fun verify(check: IdentityCheck, currencyCode: String, nonce: String): Flow<AppleIdentityStatus> =
        run {
            val currency = CurrencyCode.fromCode(currencyCode)
            val waiting = Active(check, IdentityReturn.state(nonce, currency), IdentityReturnSignal())
            active.value = waiting
            try {
                driver.verify(check, currency, nonce, waiting.signal).collect { emit(it.toApple()) }
            } finally {
                active.compareAndSet(waiting, null)
            }
        }

    fun resume(check: IdentityCheck, code: String?, error: String?, state: String?): Flow<AppleIdentityStatus> =
        run {
            val ret = IdentityReturn(check, code, error, state)
            val currency = ret.currency
            if (currency == null) {
                emit(AppleIdentityStatus.Failed("Rejected"))
            } else {
                driver.resume(ret, currency).collect { emit(it.toApple()) }
            }
        }

    /** Bad callbacks cannot consume the live signal and displace the legitimate return. */
    fun deliverReturn(check: IdentityCheck, code: String?, error: String?, state: String?): Boolean {
        val waiting = active.value ?: return false
        if (waiting.check != check || waiting.state != state) return false
        if (!active.compareAndSet(waiting, null)) return false
        waiting.signal.deliver(IdentityReturn(check, code, error, state))
        return true
    }

    @Throws(Exception::class)
    suspend fun cancelWaiting(check: IdentityCheck, currencyCode: String) {
        driver.cancelWaiting(check, CurrencyCode.fromCode(currencyCode))
    }

    @Throws(Exception::class)
    suspend fun recoverableCheck(currencyCode: String): IdentityCheck? =
        driver.recoverableCheck(CurrencyCode.fromCode(currencyCode))

    private fun run(body: suspend kotlinx.coroutines.flow.FlowCollector<AppleIdentityStatus>.() -> Unit): Flow<AppleIdentityStatus> =
        flow {
            if (!runLock.tryLock()) {
                emit(AppleIdentityStatus.Failed("Busy"))
                return@flow
            }
            try {
                body()
            } catch (
                error: CancellationException
            ) {
                throw error
            } catch (error: Exception) {
                emit(AppleIdentityStatus.Failed("Network"))
            } finally {
                runLock.unlock()
            }
        }

    companion object {
        fun create(
            account: AppleBaseAccount,
            storage: AppleIdentityStorage,
            livenessReturnUrl: String,
            passportReturnUrl: String
        ): AppleIdentityClient =
            AppleIdentityClient(
                IdentityVerificationDriver(
                    widget = IdentityWidgetClient(account.httpClient),
                    services = if (account.networkName == "mainnet") IdentityServices.MAINNET else IdentityServices.NONE,
                    returnUrl = { if (it == IdentityCheck.Liveness) livenessReturnUrl else passportReturnUrl },
                    reputationReader = ReputationReader(account.rpc, account.network),
                    resolveAccount = { account.submitters.resolve() },
                    store = AppleIdentityStore(storage),
                    nowSeconds = { Clock.System.now().epochSeconds },
                    rpc = account.rpc,
                    network = account.network,
                )
            )
    }
}

private fun IdentityStatus.toApple(): AppleIdentityStatus =
    when (this) {
        IdentityStatus.Preparing -> AppleIdentityStatus.Preparing
        is IdentityStatus.Ready -> AppleIdentityStatus.Ready(widgetUrl)
        IdentityStatus.Verifying -> AppleIdentityStatus.Verifying
        IdentityStatus.Submitting -> AppleIdentityStatus.Submitting
        is IdentityStatus.Done -> AppleIdentityStatus.Done(summary.toApple())
        is IdentityStatus.Failed -> AppleIdentityStatus.Failed(reason.name)
    }
