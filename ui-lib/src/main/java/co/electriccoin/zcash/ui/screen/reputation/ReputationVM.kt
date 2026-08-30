package co.electriccoin.zcash.ui.screen.reputation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.onramp.OnrampArgs
import co.electriccoin.zcash.ui.screen.reputation.increase.IncreaseReputationArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.justzappit.offramp.account.SmartOfframpAccountProvider
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.reputation.ReputationReader
import xyz.justzappit.offramp.reputation.ReputationSummary
import xyz.justzappit.offramp.reputation.SocialPlatform

/**
 * Where the user stands with the exchange, read fresh on every visit.
 *
 * Nothing here is cached: a completed buy credits reputation, so a value stored from the last
 * visit is stale in exactly the moment the user is most likely to look. Nothing here is computed
 * either — the limits come from the Diamond, which is the only place the effective number exists.
 */
internal class ReputationVM(
    args: ReputationArgs,
    private val navigationRouter: NavigationRouter,
    private val accountProvider: SmartOfframpAccountProvider,
    private val reputationReader: ReputationReader,
) : ViewModel() {
    private val currency = args.currency
    private var loadJob: Job? = null

    private val mutableState =
        MutableStateFlow(
            ReputationState(
                content = ReputationContent.Loading,
                primaryAction = null,
                isRaiseLimitVisible = false,
                onBack = ::onBack,
                onRaiseLimit = ::onRaiseLimit,
            ),
        )
    val state: StateFlow<ReputationState> = mutableState

    /**
     * Re-read on every appearance rather than on construction alone. A completed buy credits
     * reputation and a verification raises the limit, so the value this screen most often returns
     * to is the one most likely to have moved since it was read.
     */
    fun onScreenVisible() {
        if (loadJob?.isActive == true) return
        load()
    }

    private fun load() {
        val hadContent = mutableState.value.content is ReputationContent.Ready
        if (!hadContent) {
            mutableState.value =
                mutableState.value.copy(content = ReputationContent.Loading, primaryAction = null)
        }
        loadJob =
            viewModelScope.launch {
                val summary =
                    try {
                        reputationReader.read(accountProvider.resolve().address, currency)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        // Broad on purpose: any read failure means the same thing to the user, and the
                        // reason belongs in the log rather than on screen.
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        Twig.warn(e) { "Reputation read failed for ${currency.code}" }
                        // A failed *refresh* leaves the last good read on screen: it was true a
                        // moment ago, and blanking it over a dropped request is the worse lie.
                        if (!hadContent) mutableState.value = unreadableState()
                        return@launch
                    }
                mutableState.value = readyState(summary)
            }
    }

    private fun unreadableState() =
        mutableState.value.copy(
            content = ReputationContent.Unreadable,
            // Still let them into the verification list: a read failure is ours, not theirs.
            isRaiseLimitVisible = true,
            primaryAction = ButtonState(text = stringRes(R.string.reputation_retry), onClick = ::load),
        )

    private fun readyState(summary: ReputationSummary): ReputationState {
        if (summary.isBlacklisted) {
            return mutableState.value.copy(
                content = ReputationContent.Blacklisted,
                isRaiseLimitVisible = false,
                primaryAction = null,
            )
        }
        return mutableState.value.copy(
            content = content(summary),
            isRaiseLimitVisible = summary.canBuy && !summary.isAtCeiling,
            primaryAction =
                if (summary.canBuy) {
                    ButtonState(text = stringRes(R.string.reputation_buy), onClick = ::onBuy)
                } else {
                    ButtonState(text = stringRes(R.string.reputation_verify_to_buy), onClick = ::onRaiseLimit)
                },
        )
    }

    /**
     * The cash-out limit is deliberately absent. This screen is only ever reached on the way to a
     * buy, reputation does not gate cashing out at all, and a second limit beside the one that is
     * blocking them invites the reading that both are. The info sheet says so in words instead.
     */
    private fun content(summary: ReputationSummary) =
        ReputationContent.Ready(
            points = summary.points.toString(),
            buyLimit =
                if (summary.canBuy) {
                    stringRes(R.string.reputation_amount_usd, summary.buyLimit.usd())
                } else {
                    stringRes(R.string.reputation_limit_locked)
                },
            buyLimitCaption =
                when {
                    !summary.canBuy -> stringRes(R.string.reputation_limit_locked_caption)
                    summary.isAtCeiling -> stringRes(R.string.reputation_limit_caption_at_ceiling)
                    else -> stringRes(R.string.reputation_limit_caption)
                },
            isLocked = !summary.canBuy,
            // Listed in awards order, so the most valuable account is always first.
            verified = SocialPlatform.entries.filter { it in summary.verified }.map { summary.row(it) },
        )

    private fun ReputationSummary.row(platform: SocialPlatform) =
        PlatformRow(
            name = platform.onChainName,
            reward = stringRes(R.string.reputation_rp_amount, award(platform).toString()),
        )

    private fun Usdc6.usd(): String = toDisplayString(stripTrailingZeros = true)

    private fun onBuy() {
        // The corridor and the limit were both read a moment ago, so this goes straight to the
        // amount screen rather than back through the routing that sent 0-RP users here.
        navigationRouter.forward(OnrampArgs(currencyCode = currency.code))
    }

    private fun onRaiseLimit() {
        navigationRouter.forward(IncreaseReputationArgs(currency = currency))
    }

    private fun onBack() {
        navigationRouter.back()
    }
}
