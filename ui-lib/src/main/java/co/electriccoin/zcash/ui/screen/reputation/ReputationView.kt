package co.electriccoin.zcash.ui.screen.reputation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappCompactButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettingsGroup
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappValueCard
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue

@Composable
internal fun ReputationView(state: ReputationState) {
    val c = ZappTheme.colors
    val infoContentDescription = stringResource(R.string.reputation_info_content_description)
    var showInfo by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .imePadding(),
    ) {
        ZappScreenHeader(
            title = stringResource(R.string.reputation_title),
            right = {
                Box(
                    modifier =
                        Modifier
                            .size(INFO_TAP_TARGET.dp)
                            .clickable { showInfo = true }
                            .semantics {
                                role = Role.Button
                                contentDescription = infoContentDescription
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = c.text)
                }
            },
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = HORIZONTAL_PADDING.dp, vertical = VERTICAL_PADDING.dp),
        ) {
            ReputationContentBody(state)
        }
        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction = {
                state.primaryAction?.let { action ->
                    ZappButton(
                        text = action.text.getValue(),
                        enabled = action.isEnabled,
                        modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                        onClick = action.onClick,
                    )
                }
            },
        )
    }
    if (showInfo) ReputationInfoSheet { showInfo = false }
}

@Composable
private fun ReputationContentBody(state: ReputationState) {
    when (val content = state.content) {
        ReputationContent.Loading -> LoadingContent()
        ReputationContent.Unreadable -> UnreadableContent(state)
        ReputationContent.Blacklisted -> BlacklistedContent()
        is ReputationContent.Ready -> ReadyContent(content, state)
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = ZappTheme.colors.accent)
    }
}

@Composable
private fun UnreadableContent(state: ReputationState) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP.dp)) {
        BasicText(
            text = stringResource(R.string.reputation_unreadable_title),
            style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
        )
        Notice(stringResource(R.string.reputation_unreadable_body))
        RaiseLimitAction(state)
    }
}

@Composable
private fun BlacklistedContent() {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP.dp)) {
        BasicText(
            text = stringResource(R.string.reputation_blacklisted_title),
            style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
        )
        Notice(stringResource(R.string.reputation_blacklisted_body))
    }
}

@Composable
private fun ReadyContent(content: ReputationContent.Ready, state: ReputationState) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP.dp)) {
        ZappValueCard(
            value = content.points,
            label = stringResource(R.string.reputation_points_label),
        )
        ZappSettingsGroup(
            title = stringResource(R.string.reputation_limits_group),
            footer = content.limitsFooter.getValue(),
        ) {
            SummaryLine(stringResource(R.string.reputation_buy_limit), content.buyLimit.getValue())
            ZappRowDivider()
            SummaryLine(stringResource(R.string.reputation_max_limit), content.maxBuyLimit.getValue())
            ZappRowDivider()
            SummaryLine(stringResource(R.string.reputation_sell_limit), content.sellLimit.getValue())
        }
        if (content.verified.isNotEmpty()) {
            ZappSettingsGroup(title = stringResource(R.string.reputation_verified_group)) {
                content.verified.forEachIndexed { index, row ->
                    if (index > 0) ZappRowDivider()
                    PlatformListRow(row = row, isVerified = true)
                }
            }
        }
        if (content.unverified.isNotEmpty()) {
            ZappSettingsGroup(
                title = stringResource(R.string.reputation_unverified_group),
                footer =
                    if (content.isAtCeiling) stringResource(R.string.reputation_ceiling_footer) else null,
            ) {
                content.unverified.forEachIndexed { index, row ->
                    if (index > 0) ZappRowDivider()
                    PlatformListRow(row = row, isVerified = false)
                }
            }
        }
        RaiseLimitAction(state)
    }
}

/**
 * Always present when raising the limit is worth anything, always secondary. The bottom bar
 * carries what the user wants next; this carries what they can do about it.
 */
@Composable
private fun RaiseLimitAction(state: ReputationState) {
    if (!state.isRaiseLimitVisible) return
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ZappCompactButton(
            text = stringResource(R.string.reputation_raise_limit),
            onClick = state.onRaiseLimit,
        )
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    ZappSummaryRow(
        label = label,
        value = value,
        modifier = Modifier.padding(horizontal = ROW_PADDING.dp, vertical = ROW_VERTICAL_PADDING.dp),
    )
}

@Composable
private fun PlatformListRow(row: PlatformRow, isVerified: Boolean) {
    val c = ZappTheme.colors
    ZappRow(
        title = row.name,
        // The one computed value on this screen: what verifying this account would actually add.
        subtitle = if (isVerified) null else row.limitGain?.getValue(),
        icon = if (isVerified) Icons.Default.Check else null,
        iconTint = c.success,
        iconBackground = c.successSoft,
        titleColor = if (isVerified) c.text else c.textMuted,
        // No chevron: this group reports where the user stands, it is not a way in. Verifying is
        // one button, below, so the two never compete.
        trailing = {
            BasicText(
                text = row.reward.getValue(),
                style =
                    ZappTheme.typography.rowSubtitle
                        .copy(color = if (isVerified) c.textMuted else c.accentText),
            )
        },
    )
}

@Composable
private fun Notice(text: String) {
    Box(modifier = Modifier.fillMaxWidth().background(ZappTheme.colors.surfaceAlt).padding(NOTICE_PADDING.dp)) {
        BasicText(text, style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted))
    }
}

private const val HORIZONTAL_PADDING = 18
private const val VERTICAL_PADDING = 16
private const val BOTTOM_BAR_GAP = 12
private const val SECTION_GAP = 16
private const val ROW_PADDING = 18
private const val ROW_VERTICAL_PADDING = 14
private const val NOTICE_PADDING = 12
private const val INFO_TAP_TARGET = 48
