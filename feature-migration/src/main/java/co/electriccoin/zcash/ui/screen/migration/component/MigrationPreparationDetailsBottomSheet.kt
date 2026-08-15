package co.electriccoin.zcash.ui.screen.migration.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.common.model.migration.MigrationPreparationDetails
import co.electriccoin.zcash.ui.common.model.migration.MigrationPreparationStepDetail
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappModalBottomSheetDragHandle
import co.electriccoin.zcash.ui.design.component.zapp.zappAccentButtonColors
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.R as DesignR

/**
 * "Prepare Your Balance" detail sheet — Figma "PR App Designs Q3'26", node 5207:16023
 * (2026-08-03). Shared by MigrationReviewScreen and MigrationProgressScreen: both collapse their
 * multi-step note-split into a single "Split Balance" summary row with a "Show details" link, and
 * both open this exact sheet — see [MigrationPreparationDetails]'s doc for why the two screens
 * still compute their own [MigrationPreparationDetails] independently.
 *
 * Uses [ZashiScreenModalBottomSheet] rather than the bare modal primitive — matching this
 * codebase's convention for button-heavy sheets (HeightInfoView, SeedInfoView, IntegrationsView,
 * InfoBottomSheetView): it opens fully expanded by default (no half-open "peek" state clipping the
 * "Got it" button) AND supplies a [contentPadding] whose bottom accounts for the system nav-bar
 * inset plus a 24 dp margin, instead of a bare fixed padding that a plain [ZashiButton] can end up
 * flush against on gesture-nav devices. The content Column is ALSO scrollable as a second line of
 * defense: even at full expansion, a wallet with enough steps to exceed screen height still needs
 * to scroll to reach the button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationPreparationDetailsBottomSheet(details: MigrationPreparationDetails?) {
    if (details == null) return
    ZashiScreenModalBottomSheet(
        onDismissRequest = details.onDismiss,
        containerColor = ZappTheme.colors.surface,
        dragHandle = { ZappModalBottomSheetDragHandle() },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding())
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringRes(DesignR.string.migrationPreparationDetails_title).getValue(),
                style = ZappTheme.typography.screenTitle,
                fontWeight = FontWeight.SemiBold,
                color = ZappTheme.colors.text,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringRes(DesignR.string.migrationPreparationDetails_body, details.stepCount).getValue(),
                style = ZappTheme.typography.body,
                color = ZappTheme.colors.textMuted,
            )
            Spacer(Modifier.height(20.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(ZappTheme.colors.surfaceAlt, RectangleShape)
                        .padding(16.dp),
            ) {
                Text(
                    text = stringRes(DesignR.string.migrationPreparationDetails_stepsTitle).getValue(),
                    style = ZappTheme.typography.rowTitle,
                    fontWeight = FontWeight.SemiBold,
                    color = ZappTheme.colors.text,
                )
                Spacer(Modifier.height(16.dp))
                details.steps.forEachIndexed { i, step ->
                    PreparationStepRow(
                        number = i + 1,
                        step = step,
                        isLast = i == details.steps.lastIndex,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(ZappTheme.colors.surfaceAlt, RectangleShape)
                        .padding(12.dp),
            ) {
                Text(
                    text = stringRes(DesignR.string.migrationPreparationDetails_amountBeingSplitTitle).getValue(),
                    style = ZappTheme.typography.caption,
                    color = ZappTheme.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = details.totalAmount.getValue(),
                    style = ZappTheme.typography.caption,
                    fontWeight = FontWeight.Medium,
                    color = ZappTheme.colors.text,
                )
            }
            Spacer(Modifier.height(24.dp))
            ZashiButton(
                state =
                    ButtonState(
                        text = stringRes(DesignR.string.migration_common_gotIt),
                        onClick = details.onDismiss
                    ),
                modifier = Modifier.fillMaxWidth(),
                defaultPrimaryColors = zappAccentButtonColors(),
            )
        }
    }
}

@Composable
private fun PreparationStepRow(
    number: Int,
    step: MigrationPreparationStepDetail,
    isLast: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(bottom = if (isLast) 0.dp else 12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (!isLast) {
                // Matches MigrationProgressScreen's TransferProgressTimelineRow: the connector
                // paints green once this step is done, gray otherwise.
                val connectorColor =
                    if (step.isDone) {
                        ZappTheme.colors.success
                    } else {
                        ZappTheme.colors.border
                    }
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .padding(top = 24.dp)
                            .width(2.dp)
                            .background(connectorColor)
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .background(
                            if (step.isDone) {
                                ZappTheme.colors.success
                            } else {
                                ZappTheme.colors.surfaceAlt
                            },
                            RectangleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (step.isDone) {
                    Icon(
                        painter = painterResource(co.electriccoin.zcash.migration.R.drawable.ic_migration_check),
                        contentDescription = null,
                        tint = ZappTheme.colors.onAccent,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Text(
                        text = "$number",
                        style = ZappTheme.typography.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = ZappTheme.colors.textMuted,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(TITLE_COLUMN_WEIGHT)) {
            Text(
                text = step.title.getValue(),
                style = ZappTheme.typography.body,
                fontWeight = FontWeight.Medium,
                color = ZappTheme.colors.text,
            )
            Text(
                text = step.timeLabel.getValue(),
                style = ZappTheme.typography.caption,
                color = ZappTheme.colors.textMuted,
            )
        }
        Spacer(Modifier.width(8.dp))
        // Weighted, not a bare align: unweighted Row children measure first at full width, so a
        // long "Waits on steps 1, 2, ..., 14" squeezed the title column into one character per line.
        Text(
            text = step.statusLabel.getValue(),
            style = ZappTheme.typography.caption,
            color = ZappTheme.colors.textMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
        )
    }
}

private const val TITLE_COLUMN_WEIGHT = 1.3f
