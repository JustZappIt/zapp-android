package co.electriccoin.zcash.ui.screen.invest.sellprogress

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.common.invest.model.SellProgress
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat

/**
 * The sale's steps (UX plan I10): authorised → sold → ZEC sent to you. Stock refunded to the private account, or an
 * intent whose deadline passed unrun, both mean nothing was sold; a FAILED status needs support.
 */
internal object SellProgressToSteps {
    fun steps(
        progress: SellProgress,
        asset: InvestAsset?,
    ): List<ZappStep> =
        when (progress) {
            is SellProgress.Authorised -> {
                listOf(
                    authorised(ZappStepStatus.InProgress, stringRes(R.string.invest_sell_step_authorised_detail)),
                    selling(ZappStepStatus.Pending, asset),
                    sent(ZappStepStatus.Pending),
                )
            }

            is SellProgress.Selling -> {
                listOf(
                    authorised(ZappStepStatus.Completed),
                    selling(ZappStepStatus.InProgress, asset, stringRes(R.string.invest_sell_step_selling_detail)),
                    sent(ZappStepStatus.Pending),
                )
            }

            is SellProgress.Sent -> {
                listOf(
                    authorised(ZappStepStatus.Completed),
                    selling(ZappStepStatus.Completed, asset),
                    sent(
                        ZappStepStatus.Completed,
                        progress.zec?.let { stringRes(R.string.invest_sell_step_sent_detail, InvestFormat.zec(it)) },
                    ),
                )
            }

            is SellProgress.ReturnedToAccount -> {
                listOf(
                    authorised(ZappStepStatus.Completed),
                    ZappStep(stringRes(R.string.invest_sell_step_returned), ZappStepStatus.Completed),
                )
            }

            is SellProgress.NotSold -> {
                listOf(
                    authorised(ZappStepStatus.Completed),
                    ZappStep(stringRes(R.string.invest_sell_step_not_sold), ZappStepStatus.Failed),
                )
            }

            is SellProgress.NeedsAttention -> {
                listOf(
                    authorised(ZappStepStatus.Completed),
                    ZappStep(stringRes(R.string.invest_progress_step_attention), ZappStepStatus.Failed),
                )
            }
        }

    fun title(
        progress: SellProgress?,
        asset: InvestAsset?,
    ): StringResource =
        when {
            progress is SellProgress.Sent -> {
                stringRes(R.string.invest_sell_sent_title)
            }

            progress is SellProgress.ReturnedToAccount || progress is SellProgress.NotSold -> {
                stringRes(R.string.invest_sell_not_sold_title)
            }

            progress is SellProgress.NeedsAttention -> {
                stringRes(R.string.invest_sell_attention_title)
            }

            asset != null -> {
                stringRes(R.string.invest_sell_progress_title, asset.name)
            }

            else -> {
                stringRes(R.string.invest_sell_progress_title_unknown)
            }
        }

    fun subtitle(
        progress: SellProgress?,
        asset: InvestAsset?,
        amountText: String?,
    ): StringResource? {
        val name = asset?.name
        return when (progress) {
            is SellProgress.Sent -> {
                progress.zec?.let { stringRes(R.string.invest_sell_sent_subtitle, InvestFormat.zec(it)) }
                    ?: stringRes(R.string.invest_sell_sent_subtitle_unknown)
            }

            is SellProgress.ReturnedToAccount -> {
                name?.let { stringRes(R.string.invest_sell_returned_subtitle, it) }
                    ?: stringRes(R.string.invest_sell_returned_subtitle_unknown)
            }

            is SellProgress.NotSold -> {
                name?.let { stringRes(R.string.invest_sell_not_sold_subtitle, it) }
                    ?: stringRes(R.string.invest_sell_not_sold_subtitle_unknown)
            }

            is SellProgress.NeedsAttention -> {
                null
            }

            is SellProgress.Authorised, is SellProgress.Selling, null -> {
                amountText?.let { stringRes(R.string.invest_progress_subtitle, it) }
                    ?: stringRes(R.string.invest_progress_subtitle_no_amount)
            }
        }
    }

    private fun authorised(
        status: ZappStepStatus,
        detail: StringResource? = null,
    ) = ZappStep(stringRes(R.string.invest_sell_step_authorised), status, detailLines = listOfNotNull(detail))

    private fun selling(
        status: ZappStepStatus,
        asset: InvestAsset?,
        detail: StringResource? = null,
    ) = ZappStep(
        label =
            asset?.let { stringRes(R.string.invest_sell_step_selling, it.name) }
                ?: stringRes(R.string.invest_sell_step_selling_unknown),
        status = status,
        detailLines = listOfNotNull(detail),
    )

    private fun sent(
        status: ZappStepStatus,
        detail: StringResource? = null,
    ) = ZappStep(stringRes(R.string.invest_sell_step_sent), status, detailLines = listOfNotNull(detail))
}
