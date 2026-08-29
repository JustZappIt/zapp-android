package co.electriccoin.zcash.ui.screen.reputation

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import xyz.justzappit.offramp.reputation.SocialPlatform

internal data class ReputationState(
    val content: ReputationContent,
    /**
     * The bottom bar's one CTA — whatever the user wants next, which is buying as soon as they
     * can. Null only when nothing is actionable (blacklisted), where an enabled-looking button
     * would be a lie and a disabled one furniture.
     */
    val primaryAction: ButtonState?,
    /**
     * Raising the limit is always reachable but never the main button once buying works. Absent
     * at 0 RP, where it would duplicate the primary, and at the ceiling, where it buys nothing.
     */
    val isRaiseLimitVisible: Boolean,
    val onBack: () -> Unit,
    val onRaiseLimit: () -> Unit,
)

internal sealed interface ReputationContent {
    data object Loading : ReputationContent

    data class Ready(
        val points: String,
        /** The Diamond's own number, or the locked line — never a rendered "$0", which reads as a bug. */
        val buyLimit: StringResource,
        val maxBuyLimit: StringResource,
        val sellLimit: StringResource,
        val limitsFooter: StringResource,
        val verified: List<PlatformRow>,
        val unverified: List<PlatformRow>,
        val isAtCeiling: Boolean,
    ) : ReputationContent

    /** Terminal: verifying will not help, so the screen says so and offers nothing. */
    data object Blacklisted : ReputationContent

    /** Base was unreachable. Never rendered as 0 RP — that shows a verified user a wall. */
    data object Unreadable : ReputationContent
}

internal data class PlatformRow(
    val platform: SocialPlatform,
    /** The brand's own name, as the contract spells it. Not translated. */
    val name: String,
    val reward: StringResource,
    /** "+$50 to your limit", suppressed when the corridor's ratio is unreadable or at the ceiling. */
    val limitGain: StringResource?,
)
