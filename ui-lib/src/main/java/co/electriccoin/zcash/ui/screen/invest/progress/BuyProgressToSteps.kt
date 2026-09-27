package co.electriccoin.zcash.ui.screen.invest.progress

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.invest.model.BuyProgress
import co.electriccoin.zcash.ui.common.invest.model.InvestAsset
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepStatus
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestFormat

/**
 * The buy's steps (UX plan §6): ZEC sent → payment received → buying → held privately. An incomplete deposit is a
 * warning on "Payment received", not a failure: 1Click either completes or refunds it. A refund or a FAILED status
 * replaces the last two steps.
 *
 * Any other final state is the quote expiring before a deposit arrived (the buy engine's `Expired`): nothing was
 * bought, and late ZEC is refunded by 1Click. The `when`s here have no subject so that state, and any later one,
 * lands in `else` rather than breaking the build.
 */
internal object BuyProgressToSteps {
    fun steps(
        progress: BuyProgress,
        asset: InvestAsset?,
    ): List<ZappStep> =
        when {
            progress is BuyProgress.SendingZec -> {
                listOf(
                    sent(ZappStepStatus.InProgress, stringRes(R.string.invest_progress_step_sent_detail)),
                    received(ZappStepStatus.Pending),
                    buying(ZappStepStatus.Pending, asset),
                    held(ZappStepStatus.Pending),
                )
            }

            progress is BuyProgress.PaymentReceived -> {
                listOf(
                    sent(ZappStepStatus.Completed),
                    received(
                        ZappStepStatus.InProgress,
                        stringRes(
                            if (progress.incomplete) {
                                R.string.invest_progress_step_received_incomplete
                            } else {
                                R.string.invest_progress_step_received_detail
                            },
                        ),
                    ),
                    buying(ZappStepStatus.Pending, asset),
                    held(ZappStepStatus.Pending),
                )
            }

            progress is BuyProgress.Buying -> {
                listOf(
                    sent(ZappStepStatus.Completed),
                    received(ZappStepStatus.Completed),
                    buying(ZappStepStatus.InProgress, asset, stringRes(R.string.invest_progress_step_buying_detail)),
                    held(ZappStepStatus.Pending),
                )
            }

            progress is BuyProgress.Held -> {
                listOf(
                    sent(ZappStepStatus.Completed),
                    received(ZappStepStatus.Completed),
                    buying(ZappStepStatus.Completed, asset),
                    held(ZappStepStatus.Completed, stringRes(R.string.invest_progress_step_held_detail)),
                )
            }

            progress is BuyProgress.Refunded -> {
                listOf(
                    sent(ZappStepStatus.Completed),
                    received(ZappStepStatus.Completed),
                    ZappStep(
                        label = stringRes(R.string.invest_progress_step_refunded),
                        status = ZappStepStatus.Completed,
                        detailLines =
                            listOfNotNull(
                                progress.zec?.let {
                                    stringRes(R.string.invest_progress_step_refunded_detail, InvestFormat.zec(it))
                                },
                            ),
                    ),
                )
            }

            progress is BuyProgress.NeedsAttention -> {
                listOf(
                    sent(ZappStepStatus.Completed),
                    received(ZappStepStatus.Completed),
                    ZappStep(stringRes(R.string.invest_progress_step_attention), ZappStepStatus.Failed),
                )
            }

            progress.isFinal -> {
                listOf(ZappStep(stringRes(R.string.invest_progress_step_expired), ZappStepStatus.Failed))
            }

            // A state this screen doesn't know yet that isn't final: still under way.
            else -> {
                listOf(
                    sent(ZappStepStatus.InProgress),
                    received(ZappStepStatus.Pending),
                    buying(ZappStepStatus.Pending, asset),
                    held(ZappStepStatus.Pending),
                )
            }
        }

    fun title(
        progress: BuyProgress?,
        asset: InvestAsset?,
    ): StringResource =
        when {
            progress is BuyProgress.Held -> stringRes(R.string.invest_progress_held_title)
            progress is BuyProgress.Refunded -> stringRes(R.string.invest_progress_refunded_title)
            progress is BuyProgress.NeedsAttention -> stringRes(R.string.invest_progress_attention_title)
            progress?.isFinal == true -> stringRes(R.string.invest_progress_expired_title)
            asset != null -> stringRes(R.string.invest_progress_title, asset.name)
            else -> stringRes(R.string.invest_progress_title_unknown)
        }

    fun subtitle(
        progress: BuyProgress?,
        asset: InvestAsset?,
        amountText: String?,
    ): StringResource? =
        when {
            progress is BuyProgress.Held -> {
                val units = progress.units
                if (units != null && asset != null) {
                    stringRes(R.string.invest_progress_held_subtitle, InvestFormat.units(units, asset.ticker))
                } else {
                    stringRes(R.string.invest_progress_held_subtitle_unknown)
                }
            }

            progress is BuyProgress.Refunded -> {
                progress.zec?.let { stringRes(R.string.invest_progress_refunded_subtitle, InvestFormat.zec(it)) }
                    ?: stringRes(R.string.invest_progress_refunded_subtitle_unknown)
            }

            progress is BuyProgress.NeedsAttention -> {
                null
            }

            progress?.isFinal == true -> {
                stringRes(R.string.invest_progress_expired_subtitle)
            }

            else -> {
                amountText?.let { stringRes(R.string.invest_progress_subtitle, it) }
                    ?: stringRes(R.string.invest_progress_subtitle_no_amount)
            }
        }

    private fun sent(
        status: ZappStepStatus,
        detail: StringResource? = null,
    ) = ZappStep(stringRes(R.string.invest_progress_step_sent), status, detailLines = listOfNotNull(detail))

    private fun received(
        status: ZappStepStatus,
        detail: StringResource? = null,
    ) = ZappStep(stringRes(R.string.invest_progress_step_received), status, detailLines = listOfNotNull(detail))

    private fun buying(
        status: ZappStepStatus,
        asset: InvestAsset?,
        detail: StringResource? = null,
    ) = ZappStep(
        label =
            asset?.let { stringRes(R.string.invest_progress_step_buying, it.name) }
                ?: stringRes(R.string.invest_progress_step_buying_unknown),
        status = status,
        detailLines = listOfNotNull(detail),
    )

    private fun held(
        status: ZappStepStatus,
        detail: StringResource? = null,
    ) = ZappStep(stringRes(R.string.invest_progress_step_held), status, detailLines = listOfNotNull(detail))
}
