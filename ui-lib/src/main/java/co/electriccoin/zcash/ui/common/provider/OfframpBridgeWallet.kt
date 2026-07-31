package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AFFILIATE_ADDRESS
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.datasource.TokenNotFoundException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.model.near.requireQuoteMatchesUserAmount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.funding.FundingOutcome
import xyz.justzappit.offramp.funding.OfframpFunding
import xyz.justzappit.offramp.funding.OfframpRefund
import xyz.justzappit.offramp.funding.OfframpTopUp
import xyz.justzappit.offramp.orchestrator.OfframpRequest
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.p2p.getUsdcBalance
import java.math.BigDecimal
import kotlin.time.Clock

/**
 * Wallet-side half of the offramp NEAR bridge that offramp-lib (pure JVM) can't provide: the user's
 * Zcash address (1-Click `refundTo` for bridge-in, recipient for pull-back) and the **user-confirmed**
 * ZEC deposit send. Implemented by [RealOfframpBridgeWallet], which reuses the app's existing send
 * pipeline. Mainnet offramp still stays gated by `ProviderModule`'s account-provider kill-switch until
 * a mainnet account source + end-to-end validation land, so this seam isn't hit in a shipped build yet.
 */
interface OfframpBridgeWallet {
    /** Wallet receive address used as 1-Click `refundTo` (bridge-in) and recipient (pull-back). */
    suspend fun zcashAddress(): String

    /**
     * Sends the bridge's ZEC input (per [quote]) to the 1-Click deposit address with explicit user
     * authorization, returning the Zcash deposit tx id once submitted.
     */
    suspend fun sendZecDeposit(quote: SwapQuote): String

    /** The wallet's spendable shielded ZEC, used to gate a bridge before any ZEC is sent. */
    suspend fun spendableZec(): Zatoshi
}

/**
 * Reuses the app's existing send pipeline to fund the bridge: build a swap proposal for the 1-Click
 * [quote], authorize + submit through [SubmitProposalUseCase] (biometrics → proposal-repository submit,
 * which for a swap proposal also notifies 1-Click of the deposit tx), then await the terminal submit
 * state for the deposit tx id. The swap UI flow itself is not modified — only its repositories/use case
 * are reused, exactly as `RequestSwapQuoteUseCase` builds and submits a swap.
 *
 * MAINNET-VALIDATION: a cancelled biometric prompt never resolves [submitState], so the await relies on
 * the surrounding offramp flow being cancelled by the user. Keystone signing still routes through the
 * QR sign screen — pre-Keystone-support [navigateAfter=false] is Zashi-only; the Keystone path will
 * need a separate seam.
 */
class RealOfframpBridgeWallet(
    private val accountDataSource: AccountDataSource,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val submitProposal: SubmitProposalUseCase,
    private val synchronizerProvider: SynchronizerProvider,
) : OfframpBridgeWallet {
    override suspend fun zcashAddress(): String = accountDataSource.requestNextShieldedAddress().address

    override suspend fun spendableZec(): Zatoshi = accountDataSource.getSelectedAccount().spendableShieldedBalance

    override suspend fun sendZecDeposit(quote: SwapQuote): String {
        val send =
            ZecSend(
                destination = walletAddress(quote.depositAddress.address),
                amount = Zatoshi(quote.amountIn.toLong()),
                memo = Memo(""),
                proposal = null,
            )
        // Build the proposal the same way the swap flow does, per account type.
        val submitState: Flow<SubmitProposalState?> =
            when (accountDataSource.getSelectedAccount()) {
                is KeystoneAccount -> {
                    keystoneProposalRepository.createExactOutputSwapProposal(send, quote)
                    keystoneProposalRepository.createPCZTFromProposal()
                    keystoneProposalRepository.submitState
                }

                is ZashiAccount -> {
                    zashiProposalRepository.createExactOutputSwapProposal(send, quote)
                    zashiProposalRepository.submitState
                }
            }
        // Keep the user on the offramp progress screen. `navigateAfter = false` suppresses the
        // default replace(TransactionProgressArgs) the standard send/swap UX relies on — see
        // SubmitProposalUseCase.invoke kdoc. The Zashi submit still runs on a background coroutine
        // and `submitState` resolves the same way.
        submitProposal(navigateAfter = false)
        val result = submitState.filterIsInstance<SubmitProposalState.Result>().first().submitResult
        return when (result) {
            is SubmitResult.Success -> {
                result.txIds.firstOrNull()
                    ?: error("ZEC bridge deposit submitted but returned no transaction id")
            }

            else -> {
                error("ZEC bridge deposit did not succeed: $result")
            }
        }
    }

    private suspend fun walletAddress(address: String): WalletAddress =
        when (val r = synchronizerProvider.getSynchronizer().validateAddress(address)) {
            AddressType.Shielded -> WalletAddress.Sapling.new(address)
            AddressType.Tex -> WalletAddress.Tex.new(address)
            AddressType.Transparent -> WalletAddress.Transparent.new(address)
            AddressType.Unified -> WalletAddress.Unified.new(address)
            is AddressType.Invalid -> error("1-Click deposit address invalid: ${r.reason}")
        }
}

private const val ZEC_BRIDGE_FEE_RESERVE_ZAT = 10_000L
internal val ZEC_BRIDGE_FEE_RESERVE: Zatoshi = Zatoshi(ZEC_BRIDGE_FEE_RESERVE_ZAT)

internal fun isSpendableZecSufficientForBridge(
    spendableZec: Zatoshi,
    requiredZec: Zatoshi,
    feeReserveZec: Zatoshi = ZEC_BRIDGE_FEE_RESERVE,
): Boolean = spendableZec.value >= requiredZec.value + feeReserveZec.value

internal data class BridgeGate(
    val canSubmit: Boolean,
    val isInsufficient: Boolean,
)

internal fun evaluateBridgeGate(
    hasEnteredAmount: Boolean,
    requiredZec: Zatoshi?,
    spendableZec: Zatoshi,
    feeReserveZec: Zatoshi = ZEC_BRIDGE_FEE_RESERVE,
): BridgeGate {
    val affordable =
        requiredZec != null && isSpendableZecSufficientForBridge(spendableZec, requiredZec, feeReserveZec)
    return BridgeGate(
        canSubmit = hasEnteredAmount && affordable,
        isInsufficient = hasEnteredAmount && requiredZec != null && !affordable,
    )
}

/**
 * Read-only quote for the "Add funds to Base" screen: the ZEC the bridge will require for the entered
 * amount (so the screen can check it against the wallet's spendable balance) and 1-Click's estimated
 * time-to-settle. No ZEC moves.
 */
data class OfframpTopUpEstimate(
    val requiredZec: Zatoshi,
    /** 1-Click's estimated time-to-settle in seconds, or null when the provider omits it. */
    val estimatedDurationSeconds: Int?,
    val feeReserveZec: Zatoshi = ZEC_BRIDGE_FEE_RESERVE,
    val affiliateFeeZec: Zatoshi? = null,
    val slippagePercent: BigDecimal? = null,
) {
    val spendableZecRequired: Zatoshi = Zatoshi(requiredZec.value + feeReserveZec.value)
}

/**
 * Mainnet quotes 1-Click for the estimate; testnet (no route) binds a no-op returning null.
 */
fun interface OfframpTopUpPreview {
    suspend fun estimate(account: Address, usdc: Usdc6): OfframpTopUpEstimate?
}

/**
 * Mainnet funding: bridges ZEC → USDC into the smart account via NEAR 1-Click, **reusing** the app's
 * existing [SwapDataSource] for the quote and status polling (it is not modified here). The bridge is
 * `EXACT_OUTPUT` so it delivers exactly the order's USDC and refunds any excess ZEC to the user.
 *
 * Resumable + idempotent (per [OfframpFunding]): a non-null `resumeHandle` re-polls the already-opened
 * deposit address instead of quoting a second bridge, and `onBridgeStarted` fires the moment the deposit
 * address is known — before any ZEC moves — so the orchestrator persists it first.
 */
class NearBridgeOfframpFunding(
    private val rpc: BaseRpcClient,
    private val usdc: Address,
    private val swapDataSource: SwapDataSource,
    private val wallet: OfframpBridgeWallet,
    private val slippageTolerancePercent: BigDecimal = DEFAULT_SLIPPAGE_PERCENT,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : OfframpFunding,
    OfframpTopUp,
    OfframpTopUpPreview {
    override suspend fun ensureFunded(
        account: Address,
        request: OfframpRequest,
        resumeHandle: String?,
        onBridgeStarted: suspend (depositAddress: String) -> Unit,
    ): FundingOutcome {
        val initialBalance = rpc.getUsdcBalance(usdc, account)
        if (initialBalance >= request.usdcAmount) {
            return FundingOutcome.AlreadyFunded(currentBalance = initialBalance)
        }

        val tokens = swapDataSource.getSupportedTokens()
        val depositAddress =
            if (resumeHandle != null) {
                // Re-emit so the UI's BridgingFunds row repaints with the persisted address on resume.
                onBridgeStarted(resumeHandle)
                resumeHandle
            } else {
                openBridge(account, request.usdcAmount, tokens, onBridgeStarted)
            }

        pollUntilSettled(depositAddress, tokens)
        check(rpc.getUsdcBalance(usdc, account) >= request.usdcAmount) {
            "NEAR bridge settled but ${account.checksumHex} is still under-funded for the order."
        }
        return FundingOutcome.Bridged(depositAddress = depositAddress)
    }

    /**
     * Top-up path: bridge exactly [usdc] onto [account] with no AlreadyFunded short-circuit — the user
     * has deliberately chosen to add this much to their reusable Base balance even if it already holds
     * some. Reports success on 1-Click SUCCESS without asserting a balance increase: on a resume the
     * USDC may have landed in a prior session, so an increase check would false-fail a completed bridge.
     * The fail-closed guards are the quote-echo + destination-address checks in [openBridge], asserted
     * before any ZEC is sent.
     */
    override suspend fun bridge(
        account: Address,
        usdc: Usdc6,
        resumeHandle: String?,
        onBridgeStarted: suspend (depositAddress: String) -> Unit,
    ): FundingOutcome {
        val tokens = swapDataSource.getSupportedTokens()
        val depositAddress =
            if (resumeHandle != null) {
                onBridgeStarted(resumeHandle)
                resumeHandle
            } else {
                openBridge(account, usdc, tokens, onBridgeStarted) { requiredZec ->
                    val spendable = wallet.spendableZec()
                    if (!isSpendableZecSufficientForBridge(spendable, requiredZec)) {
                        throw InsufficientZecForBridgeException(requiredZec = requiredZec, spendableZec = spendable)
                    }
                }
            }

        // pollUntilSettled returns only on 1-Click SUCCESS (terminal states throw). No balance-delta
        // assertion here: on a resume the USDC may have already landed in a prior session, so it won't
        // increase during this re-poll — checking for an increase would false-fail a completed bridge.
        pollUntilSettled(depositAddress, tokens)
        return FundingOutcome.Bridged(depositAddress = depositAddress)
    }

    /**
     * Read-only ZEC→USDC quote for the top-up bridge UI: returns 1-Click's estimated time-to-settle in
     * seconds, or null when the provider omits it (the UI then shows a static estimate). No ZEC moves.
     */
    override suspend fun estimate(account: Address, usdc: Usdc6): OfframpTopUpEstimate? =
        runCatching {
            val tokens = swapDataSource.getSupportedTokens()
            val quote = requestBridgeQuote(account, usdc, tokens, refundAddress = wallet.zcashAddress())
            OfframpTopUpEstimate(
                requiredZec = Zatoshi(quote.amountIn.toLong()),
                estimatedDurationSeconds = quote.estimatedDurationSeconds,
                affiliateFeeZec = quote.affiliateFeeZatoshi,
                slippagePercent = quote.slippage,
            )
        }.getOrNull()

    private suspend fun requestBridgeQuote(
        account: Address,
        amount: Usdc6,
        tokens: List<SwapAsset>,
        refundAddress: String,
    ): SwapQuote =
        swapDataSource.requestQuote(
            swapMode = SwapMode.EXACT_OUTPUT,
            flexInput = false,
            amount = amount.whole,
            refundAddress = refundAddress,
            originAsset = tokens.zecAsset(),
            destinationAddress = account.checksumHex,
            destinationAsset = tokens.usdcAsset(usdc),
            slippage = slippageTolerancePercent,
            affiliateAddress = AFFILIATE_ADDRESS,
        )

    private suspend fun openBridge(
        account: Address,
        amount: Usdc6,
        tokens: List<SwapAsset>,
        onBridgeStarted: suspend (depositAddress: String) -> Unit,
        guardSufficientZec: suspend (requiredZec: Zatoshi) -> Unit = {},
    ): String {
        val refundAddress = wallet.zcashAddress()
        val quote = requestBridgeQuote(account, amount, tokens, refundAddress)
        // This call site bypasses RequestSwapQuoteUseCase's validateQuote layer, so assert the quote
        // echo here before irreversibly sending ZEC to quote.depositAddress. The asset ids are not
        // asserted: 1Click rewrites them for routing, so the echo carries no authorization.
        requireQuoteMatchesUserAmount(
            quoted = quote.amountOutFormatted,
            requested = amount.whole,
            decimals = quote.destinationAsset.decimals
        )
        requireMatchingAddress(
            name = "destinationAddress",
            expected = account.checksumHex,
            actual = quote.destinationAddress.address
        )
        requireMatchingAddress(
            name = "refundAddress",
            expected = refundAddress,
            actual = quote.refundAddress.address
        )
        // Now that the real ZEC input is known, fail before persisting the deposit handle or sending —
        // an insufficient-balance throw here leaves no checkpoint to resume and no ZEC in flight.
        guardSufficientZec(Zatoshi(quote.amountIn.toLong()))
        val depositAddress = quote.depositAddress.address
        // Persist the 1-Click handle BEFORE any ZEC moves; a crash between send and persist would
        // otherwise let resume open a second bridge and double-send the user's ZEC.
        onBridgeStarted(depositAddress)
        wallet.sendZecDeposit(quote)
        return depositAddress
    }

    /**
     * The bridge runs server-side on 1-Click regardless of our polling cadence, so a transient
     * HTTP error here (wifi blip, 5xx, transient timeout) must not propagate — bubbling it would
     * make the orchestrator emit Failed(FUNDING), clear the checkpoint, and orphan the user's
     * in-flight ZEC with no resume path. Only a *terminal* [SwapStatus] from 1-Click counts as
     * the bridge actually dying — and in that case we throw [BridgeTerminallyFailedException],
     * which the checkpoint persister recognises to clear the checkpoint (re-polling the same
     * handle would just yield the same terminal status forever). Cancellation escapes normally
     * for coroutine teardown.
     *
     * Deterministic failures are the exception to the swallow-and-retry rule: the fail-closed
     * status validations ([IllegalArgumentException]) and catalog lookups ([TokenNotFoundException])
     * re-fire identically on every poll of the same response, so unbounded retry would hang the
     * flow silently. They get a small consecutive-failure cap and then bubble — which is
     * resume-safe, because the persister keeps the checkpoint (deposit address) for a non-terminal
     * funding failure, so a retry re-polls instead of double-sending.
     */
    private suspend fun pollUntilSettled(depositAddress: String, tokens: List<SwapAsset>) {
        var deterministicFailures = 0
        while (true) {
            val status =
                try {
                    val result = swapDataSource.checkSwapStatus(depositAddress, tokens).status
                    deterministicFailures = 0
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable
                ) {
                    if (e is IllegalArgumentException || e is TokenNotFoundException) {
                        deterministicFailures++
                        if (deterministicFailures >= MAX_DETERMINISTIC_FAILURES) throw e
                    }
                    Twig.warn(e) {
                        "NearBridgeOfframpFunding.pollUntilSettled: transient checkSwapStatus failure " +
                            "for $depositAddress — retrying in ${pollIntervalMs}ms"
                    }
                    delay(pollIntervalMs)
                    continue
                }
            when (status) {
                SwapStatus.SUCCESS -> {
                    return
                }

                SwapStatus.REFUNDED, SwapStatus.FAILED, SwapStatus.EXPIRED, SwapStatus.INCOMPLETE_DEPOSIT -> {
                    throw BridgeTerminallyFailedException(terminalStatus = status, depositAddress = depositAddress)
                }

                else -> {
                    delay(pollIntervalMs)
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 5_000L
        const val MAX_DETERMINISTIC_FAILURES = 3
        val DEFAULT_SLIPPAGE_PERCENT: BigDecimal = BigDecimal("1")
    }
}

/**
 * Surfaces a non-recoverable terminal 1-Click [SwapStatus] from [NearBridgeOfframpFunding]. The
 * type is the structural signal `OfframpCheckpointPersister` uses to clear the checkpoint —
 * substring-matching `Failed.message` would be fragile to copy edits.
 */
class BridgeTerminallyFailedException(
    val terminalStatus: SwapStatus,
    val depositAddress: String,
) : RuntimeException(
        "NEAR bridge for $depositAddress reached terminal state $terminalStatus — the bridge cannot be resumed. " +
            "If your ZEC was refunded by 1-Click it should appear at your wallet's refund address shortly.",
    )

/**
 * The wallet's spendable ZEC can't cover the bridge's required input. Thrown before any deposit handle
 * is persisted or ZEC is sent, so no checkpoint is left and nothing is in flight; the UI keys off the
 * type to show a specific shortfall message instead of a generic failure.
 */
class InsufficientZecForBridgeException(
    val requiredZec: Zatoshi,
    val spendableZec: Zatoshi,
) : RuntimeException(
        "NEAR bridge needs ${requiredZec.value} zat but only ${spendableZec.value} zat is spendable.",
    )

/**
 * Mainnet pull-back: resolves a NEAR 1-Click deposit address for a USDC → ZEC swap so the orchestrator
 * can transfer refunded USDC to it and the user receives ZEC. Reuses [SwapDataSource] for the quote; the
 * returned address is on Base (origin = USDC on Base), which is what the sponsored `USDC.transfer` targets.
 */
class NearPullbackOfframpRefund(
    private val usdc: Address,
    private val swapDataSource: SwapDataSource,
    private val wallet: OfframpBridgeWallet,
    private val slippageTolerancePercent: BigDecimal = BigDecimal("1"),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : OfframpRefund {
    override suspend fun pullbackTarget(account: Address, amount: Usdc6): Address {
        val tokens = swapDataSource.getSupportedTokens()
        val destinationAddress = wallet.zcashAddress()
        val quote =
            swapDataSource.requestQuote(
                swapMode = SwapMode.EXACT_INPUT,
                flexInput = false,
                amount = amount.whole,
                refundAddress = account.checksumHex,
                originAsset = tokens.usdcAsset(usdc),
                destinationAddress = destinationAddress,
                destinationAsset = tokens.zecAsset(),
                slippage = slippageTolerancePercent,
                affiliateAddress = AFFILIATE_ADDRESS,
            )
        // Bypasses RequestSwapQuoteUseCase's validateQuote layer — the returned deposit address is
        // where the sponsored USDC.transfer sends the user's refund, so assert the quote echo first.
        requireQuoteMatchesUserAmount(
            quoted = quote.amountInFormatted,
            requested = amount.whole,
            decimals = quote.originAsset.decimals
        )
        requireMatchingAddress(
            name = "refundAddress",
            expected = account.checksumHex,
            actual = quote.refundAddress.address
        )
        requireMatchingAddress(
            name = "destinationAddress",
            expected = destinationAddress,
            actual = quote.destinationAddress.address
        )
        require(quote.amountOut > BigDecimal.ZERO) { "Refund quote must deliver a positive ZEC amount" }
        require(quote.deadline > Clock.System.now()) { "Refund quote is already expired" }
        return Address.parse(quote.depositAddress.address)
    }

    override suspend fun awaitSettlement(handle: String) {
        val tokens = swapDataSource.getSupportedTokens()
        var deterministicFailures = 0
        while (true) {
            val status =
                try {
                    val current = swapDataSource.checkSwapStatus(handle, tokens).status
                    deterministicFailures = 0
                    current
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable
                ) {
                    if (e is IllegalArgumentException || e is TokenNotFoundException) {
                        deterministicFailures++
                        if (deterministicFailures >= MAX_DETERMINISTIC_FAILURES) throw e
                    }
                    Twig.warn(e) { "Refund bridge status failed for $handle; retrying" }
                    delay(pollIntervalMs)
                    continue
                }
            when (status) {
                SwapStatus.SUCCESS -> return

                SwapStatus.REFUNDED,
                SwapStatus.FAILED,
                SwapStatus.EXPIRED,
                SwapStatus.INCOMPLETE_DEPOSIT,
                -> throw BridgeTerminallyFailedException(status, handle)

                else -> delay(pollIntervalMs)
            }
        }
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 5_000L
        const val MAX_DETERMINISTIC_FAILURES = 3
    }
}

private fun requireMatchingAddress(name: String, expected: String, actual: String) {
    require(expected == actual) {
        "Swap quote address mismatch: expected $name=$expected but quote returned $actual"
    }
}

// ---- 1-Click supported-token lookup helpers (shared between funding + refund) ------------------

/** Picks the ZEC entry from the 1-Click supported-token catalog; throws if missing. */
private fun List<SwapAsset>.zecAsset(): SwapAsset =
    filterIsInstance<ZecSwapAsset>().firstOrNull()
        ?: error("ZEC is not in the 1-Click supported-token list")

/**
 * Picks the USDC entry by matching the configured on-chain address against the 1-Click asset id
 * (which embeds the contract, e.g. `nep141:base-0x833589…omft.near`). Lookups by raw address keep
 * the catalog network-agnostic — no hardcoded NEP asset id per network — so adding a new chain to
 * P2pNetworks doesn't drag a new constant in here.
 */
private fun List<SwapAsset>.usdcAsset(usdc: Address): SwapAsset =
    firstOrNull { it.assetId.contains(usdc.lowercaseHex.removePrefix("0x"), ignoreCase = true) }
        ?: error("USDC (${usdc.checksumHex}) is not in the 1-Click supported-token list")
