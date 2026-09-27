package co.electriccoin.zcash.ui.screen.invest.receipt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.InvestAssets
import co.electriccoin.zcash.ui.common.invest.model.InvestMarket
import co.electriccoin.zcash.ui.common.invest.repository.InvestRepository
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat
import co.electriccoin.zcash.ui.screen.invest.common.investCatching
import co.electriccoin.zcash.ui.screen.invest.progress.InvestProgressArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.ZoneId

/**
 * I11 (lite): what a buy got, from the swap record the buy engine writes (shares, fees, time) and the buy's live
 * status. Value first, at today's price; no gain or loss. The deposit address is the support reference.
 */
internal class InvestReceiptVM(
    private val args: InvestReceiptArgs,
    investRepository: InvestRepository,
    metadataRepository: MetadataRepository,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private enum class Outcome { PENDING, HELD, REFUNDED, ATTENTION, EXPIRED }

    private val isSupportOpen = MutableStateFlow(false)

    private val record =
        flow { emit(investCatching { metadataRepository.getSwapMetadata(args.depositAddress) }.getOrNull()) }
            .onStart { emit(null) }

    private val progress =
        investRepository
            .observeBuy(args.depositAddress)
            .map<BuyProgress, BuyProgress?> { it }
            .onStart { emit(null) }
            .catch { Twig.warn(it) { "InvestReceiptVM: status unavailable, showing the record's" } }

    val state: StateFlow<InvestReceiptState> =
        combine(record, progress, investRepository.market, isSupportOpen, ::buildState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = buildState(null, null, null, false),
            )

    private fun buildState(
        record: TransactionSwapMetadata?,
        progress: BuyProgress?,
        market: InvestMarket?,
        supportOpen: Boolean,
    ): InvestReceiptState {
        val asset = record?.destination?.let { InvestAssets.findBySwapTickers(it.tokenTicker, it.chainTicker) }
        val outcome = outcomeOf(progress, record?.status)
        val units =
            ((progress as? BuyProgress.Held)?.units ?: record?.amountOutFormatted)
                ?.takeIf { outcome == Outcome.HELD && it.signum() > 0 }
        val price = asset?.let { a -> market?.assets?.firstOrNull { it.asset.assetId == a.assetId }?.usdPrice }
        return InvestReceiptState(
            title = titleOf(outcome, asset),
            value = if (units != null && price != null) stringRes(InvestFormat.usd(units.multiply(price))) else null,
            units = units?.let { u -> asset?.let { stringRes(InvestFormat.units(u, it.ticker)) } },
            status = statusOf(outcome),
            isStatusDanger = outcome == Outcome.ATTENTION,
            fees = record?.totalFeesUsd?.takeIf { it.signum() > 0 }?.let { stringRes(InvestFormat.usd(it)) },
            date =
                record?.lastUpdated?.let {
                    stringResByDateTime(zonedDateTime = it.atZone(ZoneId.systemDefault()), useFullFormat = true)
                },
            reference = args.depositAddress,
            isSupportOpen = supportOpen,
            onToggleSupport = { isSupportOpen.update { !it } },
            onCopyReference = { copyToClipboard(args.depositAddress, isSensitive = false) },
            progressButton =
                if (outcome == Outcome.PENDING) {
                    ButtonState(stringRes(R.string.invest_receipt_see_progress), onClick = ::onSeeProgress)
                } else {
                    null
                },
            onBack = navigationRouter::back,
        )
    }

    private fun outcomeOf(
        progress: BuyProgress?,
        recorded: SwapStatus?,
    ): Outcome =
        when {
            progress is BuyProgress.Held -> Outcome.HELD
            progress is BuyProgress.Refunded -> Outcome.REFUNDED
            progress is BuyProgress.NeedsAttention -> Outcome.ATTENTION
            progress is BuyProgress.Expired -> Outcome.EXPIRED
            progress != null -> Outcome.PENDING
            recorded == SwapStatus.SUCCESS -> Outcome.HELD
            recorded == SwapStatus.REFUNDED -> Outcome.REFUNDED
            recorded == SwapStatus.FAILED -> Outcome.ATTENTION
            recorded == SwapStatus.EXPIRED -> Outcome.EXPIRED
            else -> Outcome.PENDING
        }

    private fun titleOf(
        outcome: Outcome,
        asset: InvestAsset?,
    ): StringResource =
        when (outcome) {
            Outcome.HELD -> {
                asset?.let { stringRes(R.string.invest_receipt_title_bought, it.name) }
                    ?: stringRes(R.string.invest_receipt_title_unknown)
            }

            Outcome.PENDING -> {
                asset?.let { stringRes(R.string.invest_progress_title, it.name) }
                    ?: stringRes(R.string.invest_progress_title_unknown)
            }

            Outcome.REFUNDED -> {
                stringRes(R.string.invest_progress_refunded_title)
            }

            Outcome.ATTENTION -> {
                stringRes(R.string.invest_progress_attention_title)
            }

            Outcome.EXPIRED -> {
                stringRes(R.string.invest_progress_expired_title)
            }
        }

    private fun statusOf(outcome: Outcome): StringResource =
        stringRes(
            when (outcome) {
                Outcome.PENDING -> R.string.invest_receipt_status_pending
                Outcome.HELD -> R.string.invest_progress_step_held
                Outcome.REFUNDED -> R.string.invest_progress_step_refunded
                Outcome.ATTENTION -> R.string.invest_progress_step_attention
                Outcome.EXPIRED -> R.string.invest_progress_step_expired
            },
        )

    private fun onSeeProgress() = navigationRouter.forward(InvestProgressArgs(depositAddress = args.depositAddress))

}
