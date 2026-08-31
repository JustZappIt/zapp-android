package co.electriccoin.zcash.ui.screen.swap.upi.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.BridgeAuthorizationCancelledException
import co.electriccoin.zcash.ui.common.provider.InsufficientZecForBridgeException
import co.electriccoin.zcash.ui.common.provider.OfframpTopUpCheckpoint
import co.electriccoin.zcash.ui.common.provider.OfframpTopUpCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.provider.OfframpTopUpPreview
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.common.provider.UnfundableBridgeHandle
import co.electriccoin.zcash.ui.common.provider.evaluateBridgeGate
import co.electriccoin.zcash.ui.common.repository.BaseBalance
import co.electriccoin.zcash.ui.common.repository.BaseBalanceRepository
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationState
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.ellipsizeMiddle
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.orchestrator.BridgeToBaseStatus
import xyz.justzappit.offramp.orchestrator.MerchantAvailability
import xyz.justzappit.offramp.orchestrator.OfframpDriver
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getPriceConfig
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * "Add funds to Base" is one explicit, biometric-authorized send. Once 1-Click opens a bridge, its
 * deposit handle is persisted so retry/process-death re-polls the same transfer instead of opening a
 * second bridge and double-sending ZEC.
 */
@Suppress("TooManyFunctions")
internal class BridgeToBaseVM(
    private val args: BridgeToBaseArgs,
    private val navigationRouter: NavigationRouter,
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val accountProvider: SmartOfframpAccountProvider,
    private val baseBalance: BaseBalanceRepository,
    private val orchestrator: OfframpDriver,
    private val topUpPreview: OfframpTopUpPreview,
    private val accountDataSource: AccountDataSource,
    private val checkpointStorage: OfframpTopUpCheckpointStorageProvider,
) : ViewModel() {
    private sealed interface Phase {
        data object Input : Phase

        data class Bridging(
            val addUsdc: Usdc6,
            val depositAddress: String?
        ) : Phase

        data class Complete(
            val addedAmount: Usdc6
        ) : Phase

        data class Failed(
            val message: StringResource,
            val resumeHandle: String?
        ) : Phase
    }

    private enum class EstimateStatus { IDLE, LOADING, LOADED, FAILED }

    // Data resolved once when the screen opens (merchant availability, sell rate, bridge ETA,
    // required ZEC for the entered amount). Bundled so the state combine stays compact.
    private data class Priming(
        // Only set when no merchant has fiat liquidity right now; null (no hint) when merchants are available.
        val unavailableWarning: StringResource? = null,
        val sellRate: BigDecimal = FALLBACK_RATE,
        val etaSeconds: Int? = null,
        val requiredZec: Zatoshi? = null,
        val estimateStatus: EstimateStatus = EstimateStatus.IDLE,
        val affiliateFeeZec: Zatoshi? = null,
        val slippagePercent: BigDecimal? = null,
    )

    // The user types INR (the deposit currency); USDC and ZEC are derived from it.
    private val inr = MutableStateFlow(NumberTextFieldInnerState())
    private val phase = MutableStateFlow<Phase>(Phase.Input)
    private val priming = MutableStateFlow(Priming())

    // Confirmation shown when the user backs out mid-bridge, so an in-flight bridge isn't silently
    // abandoned. Surfaced separately from [state]. Null = hidden.
    private val leaveConfirmation = MutableStateFlow<ZappConfirmationState?>(null)
    val leaveConfirmationState: StateFlow<ZappConfirmationState?> = leaveConfirmation.asStateFlow()

    private var smartAccountAddress: Address? = null
    private var bridgeJob: Job? = null

    // Shortfall from a payment the user couldn't yet cover; seeds the INR field once the rate resolves.
    private var pendingPrefillUsdc: Usdc6? =
        args.prefillUsdcMicro
            ?.let { runCatching { Usdc6(BigInteger(it)) }.getOrNull() }
            ?.takeIf { it > Usdc6.ZERO }

    init {
        viewModelScope.launch { resolveAndPrime() }
        // Re-probe the amount-sensitive hints (merchant availability, ETA, required ZEC) when the entered
        // amount changes. collectLatest cancels the prior block, so the leading delay debounces: typing
        // an amount costs one probe once the typing stops, not one per keystroke.
        viewModelScope.launch {
            inr
                .map { it.amount }
                .distinctUntilChanged()
                .collectLatest {
                    if (phase.value is Phase.Input) {
                        markEstimateStale()
                        delay(AMOUNT_SETTLE_DELAY_MS)
                        refreshEstimate()
                        refreshAvailability()
                    }
                }
        }
    }

    val state: StateFlow<BridgeToBaseState> =
        combine(
            inr,
            phase,
            priming,
            accountDataSource.selectedAccount,
            baseBalance.balance,
        ) { amt, currentPhase, prime, account, balance ->
            buildState(amt, currentPhase, prime, account?.spendableShieldedBalance ?: Zatoshi(0), balance)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue =
                buildState(inr.value, Phase.Input, Priming(), initialSpendableZec(), baseBalance.balance.value),
        )

    private suspend fun resolveAndPrime() {
        val account =
            runCatching { accountProvider.resolve().address }
                .onFailure { Twig.warn(it) { "BridgeToBaseVM: smart account resolve failed" } }
                .getOrNull() ?: return
        smartAccountAddress = account
        refreshRate()
        if (!resumeIfInFlight()) {
            applyPrefill()
        }
        refreshEstimate()
        refreshAvailability()
    }

    private suspend fun refreshRate() {
        runCatching { rpc.getPriceConfig(network.diamondAddress, CURRENCY).sellPriceAsRate() }
            .onSuccess { rate -> priming.update { it.copy(sellRate = rate) } }
            .onFailure { Twig.warn(it) { "BridgeToBaseVM: getPriceConfig failed" } }
    }

    // The shortfall prefill arrives as USDC; convert it to INR (rounding up so the derived USDC still
    // covers the shortfall) once the rate is known, and only if the user hasn't typed anything yet.
    private fun applyPrefill() {
        val usdc = pendingPrefillUsdc ?: return
        pendingPrefillUsdc = null
        if (inr.value.amount != null) return
        val inrAmount = usdc.whole.multiply(priming.value.sellRate).setScale(INR_INPUT_SCALE, RoundingMode.CEILING)
        inr.update { NumberTextFieldInnerState.fromAmount(inrAmount) }
    }

    // Best-effort warning, shown only when NO merchant currently has liquidity (no positive "available"
    // hint when they do). Non-blocking: the bridged USDC persists on Base and stays usable regardless.
    // The fiat side is priced at the current sell rate, because eligibility is decided on the pair —
    // a placeholder fiat amount gets a yes from circles that would refuse the real one. An unreachable
    // chain clears the warning rather than raising it: this hint is only worth showing when it is known.
    private suspend fun refreshAvailability() {
        val usdc = enteredUsdc() ?: Usdc6.ofWhole(PROBE_USDC)
        val fiat = usdc.whole.multiply(priming.value.sellRate).setScale(INR_INPUT_SCALE, RoundingMode.FLOOR)
        val availability = orchestrator.merchantAvailability(usdc, Usdc6.ofWhole(fiat), CURRENCY)
        priming.update {
            it.copy(
                unavailableWarning =
                    if (availability is MerchantAvailability.Unavailable) {
                        stringRes(R.string.bridge_to_base_merchants_unavailable)
                    } else {
                        null
                    },
            )
        }
    }

    // Drops the previous amount's figures immediately, so the debounce window never leaves a required-ZEC
    // number on screen that belongs to an amount the user has already changed.
    private fun markEstimateStale() {
        if (smartAccountAddress == null) return
        val entered = enteredUsdc()
        priming.update {
            it.copy(
                requiredZec = null,
                affiliateFeeZec = null,
                estimateStatus = if (entered != null) EstimateStatus.LOADING else EstimateStatus.IDLE,
            )
        }
    }

    // One read-only quote yields both the ETA and the ZEC the bridge will require; requiredZec is only
    // meaningful for an actually-entered amount, so it stays null while the field is empty.
    private suspend fun refreshEstimate() {
        val account = smartAccountAddress ?: return
        val entered = enteredUsdc()
        markEstimateStale()
        val probe = entered ?: Usdc6.ofWhole(PROBE_USDC)
        val estimate = topUpPreview.estimate(account, probe)
        priming.update {
            it.copy(
                etaSeconds = estimate?.estimatedDurationSeconds ?: it.etaSeconds,
                slippagePercent = estimate?.slippagePercent ?: it.slippagePercent,
                affiliateFeeZec = if (entered != null) estimate?.affiliateFeeZec else null,
                requiredZec = if (entered != null) estimate?.requiredZec else null,
                estimateStatus =
                    when {
                        entered == null -> EstimateStatus.IDLE
                        estimate?.requiredZec != null -> EstimateStatus.LOADED
                        else -> EstimateStatus.FAILED
                    },
            )
        }
    }

    private suspend fun resumeIfInFlight(): Boolean {
        val existing = (readCheckpoint() as? CheckpointRead.Ok)?.checkpoint
        val addUsdc = existing?.addUsdc()
        if (existing == null || addUsdc == null) return false
        inr.update { amountField(addUsdc) }
        startBridge(addUsdc, resumeHandle = existing.bridgeDepositAddress)
        return true
    }

    private sealed interface CheckpointRead {
        data class Ok(
            val checkpoint: OfframpTopUpCheckpoint?
        ) : CheckpointRead

        data object Failed : CheckpointRead
    }

    private suspend fun readCheckpoint(): CheckpointRead =
        try {
            CheckpointRead.Ok(checkpointStorage.get())
        } catch (e: StoreCorruptedException) {
            Twig.warn(e) { "BridgeToBaseVM: corrupted top-up checkpoint, discarding" }
            checkpointStorage.clear()
            CheckpointRead.Ok(null)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            Twig.warn(e) { "BridgeToBaseVM: top-up checkpoint read failed" }
            CheckpointRead.Failed
        }

    private fun startBridge(addUsdc: Usdc6, resumeHandle: String?) {
        if (bridgeJob?.isActive == true) return
        phase.update { Phase.Bridging(addUsdc = addUsdc, depositAddress = resumeHandle) }
        bridgeJob =
            viewModelScope.launch {
                orchestrator.bridgeToBase(addUsdc, resumeBridgeHandle = resumeHandle).collect { status ->
                    Twig.info { "BridgeToBase status=${status::class.simpleName}" }
                    onBridgeStatus(addUsdc, status)
                }
            }
    }

    private suspend fun onBridgeStatus(addUsdc: Usdc6, status: BridgeToBaseStatus) {
        when (status) {
            BridgeToBaseStatus.Idle -> {
                Unit
            }

            is BridgeToBaseStatus.Bridging -> {
                status.depositAddress?.let { depositAddress ->
                    checkpointStorage.store(
                        OfframpTopUpCheckpoint(
                            bridgeDepositAddress = depositAddress,
                            addUsdcMicroDecimal = addUsdc.micros.toString(),
                            createdAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }
                phase.update { Phase.Bridging(addUsdc = addUsdc, depositAddress = status.depositAddress) }
            }

            is BridgeToBaseStatus.Complete -> {
                checkpointStorage.clear()
                baseBalance.invalidate()
                phase.update { Phase.Complete(addedAmount = status.addedAmount) }
            }

            is BridgeToBaseStatus.Failed -> {
                val terminal = status.cause is UnfundableBridgeHandle
                val resumeHandle = if (terminal) null else status.depositAddress
                if (resumeHandle == null) checkpointStorage.clear()
                phase.update {
                    Phase.Failed(
                        message =
                            when {
                                resumeHandle != null -> {
                                    stringRes(R.string.bridge_to_base_failed_retry)
                                }

                                status.cause is InsufficientZecForBridgeException -> {
                                    val cause = status.cause as InsufficientZecForBridgeException
                                    stringRes(
                                        R.string.bridge_to_base_insufficient,
                                        zecText(cause.spendableZec),
                                    )
                                }

                                status.cause is BridgeAuthorizationCancelledException -> {
                                    stringRes(R.string.bridge_to_base_failed_cancelled)
                                }

                                terminal -> {
                                    stringRes(R.string.bridge_to_base_failed_terminal)
                                }

                                else -> {
                                    stringRes(R.string.bridge_to_base_failed_generic)
                                }
                            },
                        resumeHandle = resumeHandle,
                    )
                }
            }
        }
    }

    private fun buildState(
        amt: NumberTextFieldInnerState,
        currentPhase: Phase,
        prime: Priming,
        spendableZec: Zatoshi,
        balance: BaseBalance,
    ): BridgeToBaseState {
        val isInput = currentPhase is Phase.Input
        val isBridging = currentPhase is Phase.Bridging
        val entered = deriveUsdc(amt, prime.sellRate)
        val requiredZec = prime.requiredZec
        val hasEstimate = entered != null && requiredZec != null
        val gate =
            evaluateBridgeGate(
                hasEnteredAmount = entered != null,
                requiredZec = requiredZec,
                spendableZec = spendableZec,
            )
        return BridgeToBaseState(
            amountInput = NumberTextFieldState(innerState = amt, onValueChange = ::onAmountChange),
            baseBalanceText =
                balance.loadedOrNull?.let {
                    stringRes(R.string.upi_offramp_base_balance_label, it.toDisplayString(stripTrailingZeros = true))
                },
            usdcEquivalentText =
                (
                    entered?.let {
                        stringRes(R.string.upi_offramp_usdc_equivalent, it.toDisplayString(stripTrailingZeros = true))
                    }
                ).takeIf { isInput },
            zecToSendText =
                (
                    requiredZec?.takeIf { entered != null }?.let {
                        stringRes(R.string.bridge_to_base_zec_send, zecText(it))
                    }
                ).takeIf { isInput },
            feeText =
                prime.affiliateFeeZec
                    ?.takeIf { it.value > 0 }
                    ?.let { stringRes(R.string.bridge_to_base_fee_value, zecText(it)) }
                    ?.takeIf { isInput && hasEstimate },
            slippageText =
                prime.slippagePercent
                    ?.let { stringRes(R.string.bridge_to_base_slippage_value, it.stripTrailingZeros().toPlainString()) }
                    ?.takeIf { isInput && hasEstimate },
            quoteStatusText =
                quoteStatusText(prime.estimateStatus).takeIf { isInput && entered != null && !hasEstimate },
            isInsufficient = isInput && gate.isInsufficient,
            insufficientText =
                stringRes(R.string.bridge_to_base_insufficient, zecText(spendableZec))
                    .takeIf { isInput && gate.isInsufficient },
            etaValueText = etaValueText(prime.etaSeconds).takeIf { isInput },
            etaText = etaText(prime.etaSeconds).takeIf { isBridging },
            unavailableText = prime.unavailableWarning.takeIf { isInput },
            errorText = (currentPhase as? Phase.Failed)?.message,
            bridgingAmountText = bridgingAmountText(currentPhase, prime.sellRate),
            steps = stepsFor(currentPhase),
            isInputVisible = isInput,
            primaryButton = primaryButtonFor(currentPhase, canSubmit = gate.canSubmit),
            onBack = ::onBackRequested,
        )
    }

    private fun quoteStatusText(status: EstimateStatus): StringResource? =
        when (status) {
            EstimateStatus.LOADING -> stringRes(R.string.bridge_to_base_quote_loading)
            EstimateStatus.FAILED -> stringRes(R.string.bridge_to_base_quote_failed)
            EstimateStatus.IDLE, EstimateStatus.LOADED -> null
        }

    // Confirm before abandoning an in-flight bridge; otherwise just leave.
    private fun onBackRequested() {
        if (phase.value is Phase.Bridging) {
            leaveConfirmation.update { leaveConfirmationSheet() }
        } else {
            navigationRouter.back()
        }
    }

    private fun leaveConfirmationSheet() =
        ZappConfirmationState(
            title = stringRes(R.string.bridge_to_base_leave_title),
            message = stringRes(R.string.bridge_to_base_leave_message),
            primaryButton =
                ButtonState(
                    text = stringRes(R.string.bridge_to_base_leave_confirm),
                    onClick = {
                        leaveConfirmation.update { null }
                        navigationRouter.back()
                    },
                ),
            secondaryButton =
                ButtonState(
                    text = stringRes(R.string.bridge_to_base_leave_stay),
                    onClick = { leaveConfirmation.update { null } },
                ),
            onBack = { leaveConfirmation.update { null } },
        )

    private fun bridgingAmountText(currentPhase: Phase, sellRate: BigDecimal): StringResource? =
        when (currentPhase) {
            is Phase.Bridging -> {
                stringRes(
                    R.string.bridge_to_base_adding_amount,
                    inrText(currentPhase.addUsdc, sellRate),
                    currentPhase.addUsdc.toDisplayString(stripTrailingZeros = true),
                )
            }

            is Phase.Complete -> {
                stringRes(
                    R.string.bridge_to_base_added_amount,
                    currentPhase.addedAmount.toDisplayString(stripTrailingZeros = true),
                )
            }

            else -> {
                null
            }
        }

    private fun inrText(usdc: Usdc6, sellRate: BigDecimal): String =
        usdc.whole
            .multiply(sellRate)
            .setScale(INR_DISPLAY_SCALE, RoundingMode.FLOOR)
            .stripTrailingZeros()
            .toPlainString()

    private fun zecText(zatoshi: Zatoshi): String = zatoshi.convertZatoshiToZec().stripTrailingZeros().toPlainString()

    // Only ever the provider's own estimate (1-Click's timeEstimate, in seconds). Null — and so hidden —
    // when 1-Click doesn't supply one; we never invent a placeholder duration.
    private fun etaMinutes(seconds: Int?): Int? {
        if (seconds == null || seconds <= 0) return null
        return ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).coerceAtLeast(1)
    }

    private fun etaText(seconds: Int?): StringResource? =
        etaMinutes(seconds)?.let { stringRes(R.string.bridge_to_base_eta, it) }

    private fun etaValueText(seconds: Int?): StringResource? =
        etaMinutes(seconds)?.let { stringRes(R.string.bridge_to_base_eta_value, it) }

    private fun primaryButtonFor(currentPhase: Phase, canSubmit: Boolean): ButtonState =
        when (currentPhase) {
            Phase.Input -> {
                ButtonState(
                    text = stringRes(R.string.bridge_to_base_add_button),
                    isEnabled = canSubmit,
                    onClick = ::onAddFunds,
                )
            }

            is Phase.Bridging -> {
                ButtonState(
                    text = stringRes(R.string.bridge_to_base_bridging_button),
                    isEnabled = false,
                    onClick = {},
                )
            }

            is Phase.Complete -> {
                ButtonState(
                    text = stringRes(R.string.bridge_to_base_pay_button),
                    onClick = { navigationRouter.back() },
                )
            }

            is Phase.Failed -> {
                ButtonState(
                    text = stringRes(R.string.bridge_to_base_try_again_button),
                    onClick = ::onTryAgain,
                )
            }
        }

    private fun stepsFor(currentPhase: Phase): List<ZappStep> =
        when (currentPhase) {
            Phase.Input -> {
                emptyList()
            }

            is Phase.Bridging -> {
                listOf(
                    ZappStep(
                        label = stringRes(R.string.bridge_to_base_step_bridging),
                        status = ZappStepStatus.InProgress,
                        detailLines =
                            currentPhase.depositAddress
                                ?.let {
                                    listOf(
                                        stringRes(
                                            R.string.upi_offramp_detail_deposit_addr,
                                            it.ellipsizeMiddle(DEPOSIT_ELLIPSIS_PREFIX, DEPOSIT_ELLIPSIS_SUFFIX),
                                        ),
                                    )
                                }.orEmpty(),
                    ),
                    ZappStep(
                        label = stringRes(R.string.bridge_to_base_step_arrived),
                        status = ZappStepStatus.Pending,
                    ),
                )
            }

            is Phase.Complete -> {
                listOf(
                    ZappStep(stringRes(R.string.bridge_to_base_step_bridging), ZappStepStatus.Completed),
                    ZappStep(stringRes(R.string.bridge_to_base_step_arrived), ZappStepStatus.Completed),
                )
            }

            is Phase.Failed -> {
                listOf(
                    ZappStep(stringRes(R.string.bridge_to_base_step_bridging), ZappStepStatus.Failed),
                )
            }
        }

    private fun onAmountChange(next: NumberTextFieldInnerState) {
        inr.update { next }
    }

    private fun onAddFunds() {
        val usdc = enteredUsdc() ?: return
        viewModelScope.launch {
            when (val read = readCheckpoint()) {
                CheckpointRead.Failed -> {
                    failGeneric()
                }

                is CheckpointRead.Ok -> {
                    val existing = read.checkpoint
                    if (existing == null) {
                        startBridge(usdc, resumeHandle = null)
                    } else {
                        startBridge(existing.addUsdc() ?: usdc, resumeHandle = existing.bridgeDepositAddress)
                    }
                }
            }
        }
    }

    private fun onTryAgain() {
        val handle = (phase.value as? Phase.Failed)?.resumeHandle
        val usdc =
            enteredUsdc() ?: run {
                phase.update { Phase.Input }
                return
            }
        if (handle != null) {
            startBridge(usdc, resumeHandle = handle)
        } else {
            phase.update { Phase.Input }
        }
    }

    private fun failGeneric() {
        phase.update {
            Phase.Failed(
                message = stringRes(R.string.bridge_to_base_failed_generic),
                resumeHandle = null,
            )
        }
    }

    private fun enteredUsdc(): Usdc6? = deriveUsdc(inr.value, priming.value.sellRate)

    // INR → USDC, mirroring the pay screen's alignUsdc: floor the INR to 2dp, divide by the sell rate at
    // 6dp, and reject non-positive results so an empty/zero field yields null.
    private fun deriveUsdc(state: NumberTextFieldInnerState, sellRate: BigDecimal): Usdc6? =
        state.amount
            ?.takeIf { it > BigDecimal.ZERO }
            ?.setScale(INR_INPUT_SCALE, RoundingMode.FLOOR)
            ?.divide(sellRate, USDC_INPUT_SCALE, RoundingMode.FLOOR)
            ?.takeIf { it > BigDecimal.ZERO }
            ?.let { Usdc6.ofWhole(it) }
            ?.takeIf { it > Usdc6.ZERO }

    private fun OfframpTopUpCheckpoint.addUsdc(): Usdc6? =
        runCatching { Usdc6(BigInteger(addUsdcMicroDecimal)) }.getOrNull()?.takeIf { it > Usdc6.ZERO }

    private fun amountField(usdc: Usdc6): NumberTextFieldInnerState =
        NumberTextFieldInnerState.fromAmount(
            usdc.whole.multiply(priming.value.sellRate).setScale(INR_INPUT_SCALE, RoundingMode.CEILING),
        )

    private fun initialSpendableZec(): Zatoshi =
        accountDataSource.allAccounts.value
            .orEmpty()
            .firstOrNull { it.isSelected }
            ?.spendableShieldedBalance
            ?: Zatoshi(0)

    companion object {
        private val CURRENCY = CurrencyCode.Inr

        // Used for the USDC estimate until getPriceConfig returns; ₹85/USDC is the p2p.me historical default.
        private val FALLBACK_RATE: BigDecimal = BigDecimal("85")

        // Nominal amount for the merchant-availability and ETA probes when no amount is entered yet.
        private val PROBE_USDC: BigDecimal = BigDecimal("5")

        // How long the amount has to sit still before it is worth probing. Long enough that typing a
        // four-digit figure costs one round trip, short enough that the quote still feels immediate.
        private const val AMOUNT_SETTLE_DELAY_MS = 400L

        private const val USDC_INPUT_SCALE = 6
        private const val INR_INPUT_SCALE = 2
        private const val INR_DISPLAY_SCALE = 2
        private const val SECONDS_PER_MINUTE = 60
        private const val DEPOSIT_ELLIPSIS_PREFIX = 10
        private const val DEPOSIT_ELLIPSIS_SUFFIX = 6
    }
}
