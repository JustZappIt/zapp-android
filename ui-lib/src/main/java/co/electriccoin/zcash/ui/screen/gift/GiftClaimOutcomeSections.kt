// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappProgressBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappSuccessHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

/**
 * The terminal states of a claim, and the copy that keeps them apart.
 *
 * Split from `GiftClaimView` only to stay under detekt's per-file function count, but the grouping
 * is the meaningful one: every screen here is the last thing the recipient sees, and the difference
 * between "not confirmed yet" and "empty" is the difference between waiting fifteen minutes and
 * believing a real gift was fake.
 */
@Composable
internal fun OutcomeSection(state: GiftClaimState) {
    val spacing = ZappTheme.spacing
    Spacer(Modifier.height(spacing.xl))
    when (state.stage) {
        GiftClaimStage.DONE -> {
            ZappSuccessHeader(
                title = stringRes(R.string.gift_claim_done_title),
                subtitle = stringRes(R.string.gift_claim_done_subtitle, state.amountText?.getValue().orEmpty()),
                modifier = Modifier.padding(horizontal = spacing.xl),
            )
        }

        GiftClaimStage.PENDING_CONFIRMATIONS -> {
            PendingConfirmations(state)
        }

        else -> {
            Headline(R.string.gift_claim_empty_title, R.string.gift_claim_empty_body)
        }
    }
}

/**
 * The confirmation wait, drawn as progress rather than as a failure.
 *
 * The money is already on the card and the scan already found it; all that is left is the ten
 * confirmations a shielded note needs. A "try again" button here framed a wait as a dead end and
 * put the burden on the recipient — this fills, and the screen re-checks itself.
 */
@Composable
private fun PendingConfirmations(state: GiftClaimState) {
    val spacing = ZappTheme.spacing
    Column(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        BasicText(
            text = stringResource(R.string.gift_claim_pending_title),
            style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
        )
        ZappProgressBar(
            fraction = state.confirmationFraction,
            label = stringResource(R.string.gift_claim_pending_body),
            detail =
                state.confirmations?.let {
                    stringResource(R.string.gift_claim_pending_count, it, state.requiredConfirmations)
                },
        )
    }
}

@Composable
internal fun Headline(
    @StringRes title: Int,
    @StringRes body: Int,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Column(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        BasicText(
            text = stringResource(title),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text),
        )
        BasicText(
            text = stringResource(body),
            style = ZappTheme.typography.body.copy(color = c.textMuted),
        )
    }
}

@Composable
internal fun Caption(
    @StringRes text: Int,
) {
    val spacing = ZappTheme.spacing
    BasicText(
        text = stringResource(text),
        style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
        modifier = Modifier.padding(horizontal = spacing.xl, vertical = spacing.md),
    )
}

/** Shared by both gift screens; each maps its own error enum to a string first. */
@Composable
internal fun ErrorBanner(
    @StringRes message: Int,
) {
    val spacing = ZappTheme.spacing
    BasicText(
        text = stringResource(message),
        style = ZappTheme.typography.body.copy(color = ZappTheme.colors.danger),
        modifier = Modifier.padding(horizontal = spacing.xl, vertical = spacing.md),
    )
}

@StringRes
internal fun GiftClaimStage.subtitleRes(): Int =
    when (this) {
        GiftClaimStage.LOADING, GiftClaimStage.PREVIEW, GiftClaimStage.CONSENT -> {
            R.string.gift_claim_subtitle_preview
        }

        GiftClaimStage.NEEDS_WALLET -> {
            R.string.gift_claim_subtitle_needs_wallet
        }

        GiftClaimStage.CLAIMING -> {
            R.string.gift_claim_subtitle_claiming
        }

        GiftClaimStage.DONE, GiftClaimStage.PENDING_CONFIRMATIONS, GiftClaimStage.EMPTY -> {
            R.string.gift_claim_subtitle_done
        }
    }

@StringRes
internal fun GiftClaimError.messageRes(): Int =
    when (this) {
        GiftClaimError.MALFORMED_LINK -> R.string.gift_claim_error_link
        GiftClaimError.WRONG_NETWORK -> R.string.gift_claim_error_network
        GiftClaimError.BIRTHDAY_ABOVE_TIP -> R.string.gift_claim_error_future
        GiftClaimError.NEWER_FORMAT -> R.string.gift_claim_error_newer_format
        GiftClaimError.WALLET_NOT_READY -> R.string.gift_claim_error_not_ready
        GiftClaimError.LINK_UNAVAILABLE -> R.string.gift_claim_error_unavailable
        GiftClaimError.NOT_BROADCAST -> R.string.gift_claim_error_not_broadcast
        GiftClaimError.UNDERFUNDED -> R.string.gift_claim_error_underfunded
        GiftClaimError.UNREACHABLE -> R.string.gift_claim_error_unreachable
        GiftClaimError.PARAMS_UNAVAILABLE -> R.string.gift_claim_error_params
        GiftClaimError.FAILED -> R.string.gift_claim_error_failed
    }

/** Blocks are meaningless to a recipient; time they might have to wait is not. */
@Composable
internal fun Long.roughDuration(): String {
    val hours = this * BLOCK_SECONDS / SECONDS_PER_HOUR
    return if (hours >= HOURS_PER_DAY) {
        stringResource(R.string.gift_claim_consent_duration_days, (hours / HOURS_PER_DAY).toInt())
    } else {
        stringResource(R.string.gift_claim_consent_duration_hours, hours.coerceAtLeast(1).toInt())
    }
}

private const val BLOCK_SECONDS = 75L
private const val SECONDS_PER_HOUR = 3600L
private const val HOURS_PER_DAY = 24L
