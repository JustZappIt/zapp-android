// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.railgun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.RailgunTestAction
import co.electriccoin.zcash.ui.common.repository.RailgunWalletRepository
import co.electriccoin.zcash.ui.common.repository.RailgunWalletState
import co.electriccoin.zcash.ui.common.repository.RailgunWalletState.Phase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import xyz.justzappit.railgun.RailgunEvent
import xyz.justzappit.railgun.RailgunScanStatus
import xyz.justzappit.railgun.RailgunSent
import xyz.justzappit.railgun.RailgunTokenAmount
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.DurationUnit

class DebugRailgunVM(
    private val railgunWalletRepository: RailgunWalletRepository,
    private val copyToClipboardUseCase: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: StateFlow<DebugRailgunState> =
        railgunWalletRepository.state
            .map(::createState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createState(railgunWalletRepository.state.value),
            )

    init {
        railgunWalletRepository.refresh()
    }

    private fun createState(wallet: RailgunWalletState): DebugRailgunState {
        val isBusy = wallet.phase in listOf(Phase.STARTING, Phase.OPENING, Phase.SYNCING, Phase.SENDING)
        return DebugRailgunState(
            status =
                listOfNotNull(
                    "network: ${wallet.network?.name?.lowercase() ?: "none in mainnet builds"}",
                    "phase: ${wallet.phase.name.lowercase()}",
                    wallet.utxoScan?.let { "notes: ${it.describe()}" },
                    wallet.txidScan?.let { "transactions: ${it.describe()}" },
                    wallet.proof?.takeIf { wallet.phase == Phase.SENDING }?.let {
                        "proof: ${it.progress.toInt()}% ${it.status}".trim()
                    },
                ),
            address = wallet.address,
            onCopyAddress = { wallet.address?.let { copyToClipboardUseCase(it, isSensitive = false) } },
            balances =
                wallet.balances
                    ?.byBucket
                    .orEmpty()
                    .filterValues { it.isNotEmpty() }
                    .map { (bucket, tokens) -> bucket.name.lowercase() to tokens.map { it.describe() } },
            gasAccount =
                wallet.gasAccount?.let { listOf(it.address, "${it.balance.toDecimal(ETH_DECIMALS)} ETH") }.orEmpty(),
            onCopyGasAccount = {
                wallet.gasAccount?.let { copyToClipboardUseCase(it.address, isSensitive = false) }
            },
            activity = wallet.activity.map { (action, sent) -> describe(action, sent, short = true) },
            onCopyActivity = {
                copyToClipboardUseCase(
                    wallet.activity.joinToString("\n") { (action, sent) -> describe(action, sent, short = false) },
                    isSensitive = false,
                )
            },
            timings = wallet.timings.map { (label, duration) -> "$label: ${duration.seconds()}" },
            error = wallet.error,
            isBusy = isBusy,
            canAct = wallet.phase == Phase.READY || wallet.phase == Phase.FAILED,
            onRefresh = railgunWalletRepository::refresh,
            onShield = { railgunWalletRepository.run(RailgunTestAction.SHIELD) },
            onSend = { railgunWalletRepository.run(RailgunTestAction.SEND_TO_SELF) },
            onWithdraw = { railgunWalletRepository.run(RailgunTestAction.WITHDRAW) },
            onBack = navigationRouter::back,
        )
    }

    // The SDK reports a finished scan with progress 0.
    private fun RailgunEvent.Scan.describe() =
        if (status == RailgunScanStatus.COMPLETE) {
            "complete"
        } else {
            "${status.name.lowercase()} ${(progress * PERCENT).toInt()}%"
        }

    private fun RailgunTokenAmount.describe(): String {
        val (symbol, decimals) = KNOWN_TOKENS[token.lowercase()] ?: return "$amount of $token"
        return "${amount.toDecimal(decimals)} $symbol"
    }

    private fun describe(
        action: RailgunTestAction,
        sent: RailgunSent,
        short: Boolean,
    ): String {
        val tx = if (short) "${sent.txHash.take(HASH_PREFIX)}…" else "https://sepolia.etherscan.io/tx/${sent.txHash}"
        val proof = sent.proofDuration?.let { ", proof ${it.seconds()}" }.orEmpty()
        return "${action.name.lowercase()}$proof: $tx"
    }

    private fun BigInteger.toDecimal(decimals: Int) = BigDecimal(this, decimals).stripTrailingZeros().toPlainString()

    private fun Duration.seconds() = toString(DurationUnit.SECONDS, 1)

    private companion object {
        const val PERCENT = 100
        const val ETH_DECIMALS = 18
        const val HASH_PREFIX = 12

        // Ethereum Sepolia: Circle's test USDC (the one Railgun accepts) and WETH.
        val KNOWN_TOKENS =
            mapOf(
                "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238" to ("USDC" to 6),
                "0xfff9976782d46cc05630d1f6ebab18b2324d6b14" to ("WETH" to ETH_DECIMALS),
            )
    }
}
