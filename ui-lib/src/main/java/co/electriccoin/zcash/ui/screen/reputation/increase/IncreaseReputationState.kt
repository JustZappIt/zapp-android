package co.electriccoin.zcash.ui.screen.reputation.increase

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappStep
import co.electriccoin.zcash.ui.design.util.StringResource
import xyz.justzappit.offramp.reputation.SocialPlatform

internal data class IncreaseReputationState(
    val isLoading: Boolean,
    val platforms: List<VerifiableRow>,
    /** Non-null once a row is tapped: the run takes over the body, in place, with no new route. */
    val run: VerificationRun?,
    val error: StringResource?,
    val primaryAction: ButtonState?,
    /** Cancel, while the user is away in Reclaim. Secondary so it never competes with the primary. */
    val secondaryAction: ButtonState?,
    val onBack: () -> Unit,
    val onRetryLoad: () -> Unit,
)

internal data class VerifiableRow(
    val platform: SocialPlatform,
    /** The brand's own name, as the contract spells it. Not translated. */
    val name: String,
    val reward: StringResource,
    /**
     * What verifying this account would actually add to the buy limit — the only number on the row
     * the user is really shopping for. Null when already verified, when the corridor's RP-to-limit
     * ratio is unreadable, and at the ceiling, where the honest answer is nothing.
     */
    val limitGain: StringResource?,
    /**
     * The provider's own rule, said before the user spends five minutes finding out: a too-new
     * account comes back as a *successful* proof that verifies nothing.
     */
    val requirement: StringResource?,
    val isVerified: Boolean,
    val onClick: () -> Unit,
)

internal data class VerificationRun(
    val platform: SocialPlatform,
    val name: String,
    val stage: VerificationStage,
    val steps: List<ZappStep>,
    val message: StringResource,
    val error: StringResource?,
    /** Where the Verifier lives for this session, and the store fallback for a device without it. */
    val launchUrl: String?,
    val installIntentUrl: String?,
    /** Set only at [VerificationStage.DONE]: what the chain says now, not what we predicted. */
    val newPoints: String? = null,
    val newBuyLimit: StringResource? = null,
)

internal enum class VerificationStage {
    PREPARING,
    READY,
    VERIFYING,
    SUBMITTING,
    DONE,
    FAILED,
}
