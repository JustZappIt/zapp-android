// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.railgun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.privateusd.GasAccountDelivery
import co.electriccoin.zcash.ui.common.privateusd.GasAccountTransactions
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdTokens
import co.electriccoin.zcash.ui.common.privateusd.Sepolia
import co.electriccoin.zcash.ui.common.privateusd.toDecimal
import co.electriccoin.zcash.ui.common.repository.RailgunWalletDebug
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletState
import co.electriccoin.zcash.ui.common.repository.RailgunWalletState.Phase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.railgun.RailgunDestination
import xyz.justzappit.railgun.RailgunEvent
import xyz.justzappit.railgun.RailgunGasAccount
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunScanStatus
import xyz.justzappit.railgun.RailgunSignedTransaction
import xyz.justzappit.railgun.RailgunTokenAmount
import xyz.justzappit.railgun.RailgunTransfer
import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** A testnet round trip through the Railgun engine, paid for and sent by the gas account. */
class DebugRailgunVM(
    private val railgunWalletRepository: RailgunWalletRepository,
    private val railgunWalletDebug: RailgunWalletDebug,
    private val transactions: GasAccountTransactions,
    private val copyToClipboardUseCase: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val log = MutableStateFlow(DebugRailgunLog())

    val state: StateFlow<DebugRailgunState> =
        combine(railgunWalletRepository.state, log, ::createState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createState(railgunWalletRepository.state.value, log.value),
            )

    init {
        act("sync") { railgunWalletRepository.sync() }
    }

    private fun createState(
        wallet: RailgunWalletState,
        log: DebugRailgunLog,
    ): DebugRailgunState {
        val address = wallet.address?.value
        val gasAccount = log.gasAccount
        return DebugRailgunState(
            status =
                listOfNotNull(
                    "network: ${wallet.network?.name?.lowercase() ?: "none in mainnet builds"}",
                    "phase: ${wallet.phase.name.lowercase()}",
                    log.busy?.let { "running: $it" },
                    wallet.utxoScan?.let { "notes: ${it.describe()}" },
                    wallet.txidScan?.let { "transactions: ${it.describe()}" },
                    wallet.proof?.takeIf { wallet.phase == Phase.SIGNING }?.let {
                        "proof: ${(it.progress * PERCENT).toInt()}% ${it.status}".trim()
                    },
                ),
            address = address,
            onCopyAddress = { address?.let { copyToClipboardUseCase(it, isSensitive = false) } },
            balances =
                wallet.sync
                    ?.balances
                    ?.byBucket
                    .orEmpty()
                    .filterValues { it.isNotEmpty() }
                    .map { (bucket, tokens) ->
                        DebugRailgunBalance(bucket.name.lowercase(), tokens.map { it.describe() })
                    },
            gasAccount =
                gasAccount
                    ?.let { listOf(it.address.checksumHex, "${it.balance.toPlain(ETH_DECIMALS)} ETH") }
                    .orEmpty(),
            onCopyGasAccount = {
                gasAccount?.let { copyToClipboardUseCase(it.address.checksumHex, isSensitive = false) }
            },
            activity = log.activity.map { it.describe(short = true) },
            onCopyActivity = {
                val lines = log.activity.joinToString("\n") { it.describe(short = false) }
                copyToClipboardUseCase(lines, isSensitive = false)
            },
            timings = log.timings.map { (label, duration) -> "$label: ${duration.seconds()}" },
            error = log.error ?: wallet.error,
            isBusy = log.busy != null,
            canAct = log.busy == null && address != null,
            onRefresh = { act("sync") { railgunWalletRepository.sync() } },
            onShield = { spend(DebugRailgunAction.SHIELD) { railgunWalletDebug.signShield(SHIELD_AMOUNT) } },
            onSend = {
                wallet.address?.let { own ->
                    spend(DebugRailgunAction.SEND_TO_SELF) {
                        railgunWalletRepository.sign(transfer(RailgunDestination.Private(own)))
                    }
                }
            },
            onWithdraw = {
                gasAccount?.let {
                    spend(DebugRailgunAction.WITHDRAW) {
                        railgunWalletRepository.sign(transfer(RailgunDestination.Public(it.address)))
                    }
                }
            },
            onBack = navigationRouter::back,
        )
    }

    private fun spend(
        action: DebugRailgunAction,
        sign: suspend () -> RailgunSignedTransaction
    ) = act(action.name.lowercase()) {
        val signed = sign()
        val delivery = transactions.deliver(signed.raw, signed.txHash)
        log.update { it.copy(activity = it.activity + DebugRailgunActivity(action, signed, delivery)) }
        railgunWalletRepository.sync()
    }

    // One at a time; the gas account's balance is read again after each.
    private fun act(
        label: String,
        block: suspend () -> Unit
    ) {
        if (log.value.busy != null) return
        log.update { it.copy(busy = label, error = null) }
        viewModelScope.launch {
            val started = TimeSource.Monotonic.markNow()
            try {
                block()
                log.update { it.copy(gasAccount = railgunWalletDebug.gasAccount()) }
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                log.update { it.copy(error = ignored.message ?: ignored::class.simpleName) }
            } finally {
                log.update { it.timed(label, started.elapsedNow()).copy(busy = null) }
            }
        }
    }

    private companion object {
        const val ETH_DECIMALS = 18

        // 0.01 ETH shielded, and 0.001 WETH sent or withdrawn.
        val SHIELD_AMOUNT: BigInteger = BigInteger.TEN.pow(16)
        val SEND_AMOUNT: BigInteger = BigInteger.TEN.pow(15)

        fun transfer(to: RailgunDestination) = RailgunTransfer(to, Sepolia.WETH, SEND_AMOUNT)
    }
}

private enum class DebugRailgunAction { SHIELD, SEND_TO_SELF, WITHDRAW }

private data class DebugRailgunActivity(
    val action: DebugRailgunAction,
    val signed: RailgunSignedTransaction,
    val delivery: GasAccountDelivery,
) {
    fun describe(short: Boolean): String {
        val hash = signed.txHash.hex
        val tx = if (short) "${hash.take(HASH_PREFIX)}…" else Sepolia.EXPLORER_TX_URL + hash
        val proof = signed.proofDuration?.let { ", proof ${it.seconds()}" }.orEmpty()
        return "${action.name.lowercase()}$proof, ${delivery.name.lowercase()}: $tx"
    }
}

private data class DebugRailgunTiming(
    val label: String,
    val duration: Duration,
)

private data class DebugRailgunLog(
    val busy: String? = null,
    val error: String? = null,
    val gasAccount: RailgunGasAccount? = null,
    val activity: List<DebugRailgunActivity> = emptyList(),
    val timings: List<DebugRailgunTiming> = emptyList(),
) {
    fun timed(
        label: String,
        duration: Duration
    ) = copy(timings = (timings + DebugRailgunTiming(label, duration)).takeLast(MAX_TIMINGS))
}

// The SDK reports a finished scan with progress 0.
private fun RailgunEvent.Scan.describe() =
    if (status == RailgunScanStatus.COMPLETE) {
        "complete"
    } else {
        "${status.name.lowercase()} ${(progress * PERCENT).toInt()}%"
    }

private fun RailgunTokenAmount.describe(): String =
    PrivateUsdTokens.find(RailgunNetwork.SEPOLIA, token)?.let { "${amount.toPlain(it.decimals)} ${it.symbol}" }
        ?: "$amount of $token"

private fun BigInteger.toPlain(decimals: Int) = toDecimal(decimals).stripTrailingZeros().toPlainString()

private fun Duration.seconds() = toString(DurationUnit.SECONDS, 1)

private const val PERCENT = 100
private const val HASH_PREFIX = 12
private const val MAX_TIMINGS = 8
