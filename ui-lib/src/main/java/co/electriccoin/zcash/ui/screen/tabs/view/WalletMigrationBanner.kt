package co.electriccoin.zcash.ui.screen.tabs.view

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappCompactButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.home.migration.MigrationBannerPhase
import co.electriccoin.zcash.ui.screen.home.migration.MigrationMessageState
import co.electriccoin.zcash.ui.design.R as DesignR

@Composable
internal fun WalletMigrationBanner(
    state: MigrationMessageState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val isAttention = state.phase == MigrationBannerPhase.ATTENTION
    val accent = if (isAttention) c.danger else c.accent
    val label = state.title ?: stringResource(defaultTitleFor(state.phase))
    val detail = state.progressLabel ?: stringResource(DesignR.string.migrationHome_defaultSubtitle)
    val onCardClick = state.onClick ?: state.onButtonClick

    ZappBorderedCard(
        modifier =
            modifier
                .clickable(onClick = onCardClick)
                .semantics(mergeDescendants = true) { role = Role.Button },
        borderColor = if (isAttention) c.danger else c.border,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZappSectionLabel(text = label, color = accent)
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = detail,
                    style =
                        ZappTheme.typography.rowTitle.copy(
                            color = c.text,
                            fontWeight = FontWeight.Black,
                        ),
                )
            }
            Spacer(Modifier.width(12.dp))
            ZappCompactButton(
                text = stringResource(R.string.general_more),
                onClick = state.onButtonClick,
            )
        }

        if (state.phase == MigrationBannerPhase.IN_PROGRESS) {
            Spacer(Modifier.height(12.dp))
            MigrationProgressRule(
                percent = state.progressPercent ?: 0f,
                fillColor = accent,
            )
        }
    }
}

@Composable
private fun MigrationProgressRule(
    percent: Float,
    fillColor: Color,
) {
    val fraction = percent.coerceIn(0f, PERCENT_MAX) / PERCENT_MAX
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(RULE_HEIGHT.dp)
                .background(ZappTheme.colors.border, RectangleShape),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(MIN_VISIBLE_FRACTION))
                    .height(RULE_HEIGHT.dp)
                    .background(fillColor, RectangleShape),
        )
    }
}

@StringRes
private fun defaultTitleFor(phase: MigrationBannerPhase) =
    when (phase) {
        MigrationBannerPhase.REQUIRED -> DesignR.string.migrationHome_requiredTitle
        MigrationBannerPhase.IN_PROGRESS -> DesignR.string.migration_common_progressTitle
        MigrationBannerPhase.COMPLETE -> DesignR.string.migrationHome_completeTitle
        MigrationBannerPhase.READY_TO_SEND -> DesignR.string.migrationHome_readyToSendTitle
        MigrationBannerPhase.ATTENTION -> DesignR.string.migrationHome_attentionTitle
    }

private const val PERCENT_MAX = 100f
private const val RULE_HEIGHT = 3
private const val MIN_VISIBLE_FRACTION = 0.02f
