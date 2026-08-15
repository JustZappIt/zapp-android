package co.electriccoin.zcash.ui.screen.migration.invalid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.model.migration.MigrationAttentionKind
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.zapp.zappAccentButtonColors
import co.electriccoin.zcash.ui.design.component.zapp.zappTopAppBarColors
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import co.electriccoin.zcash.ui.design.R as DesignR

@Serializable
data object MigrationTransferInvalidArgs

@Composable
fun MigrationTransferInvalidScreen() {
    val vm = koinViewModel<MigrationTransferInvalidVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(
        state = state,
        loading = { isLoading -> if (isLoading && state.content == null) ZappScreenProgressIndicator() },
    ) { s ->
        BackHandler { s.onBack() }
        MigrationTransferInvalidView(s)
    }
}

@Composable
fun MigrationTransferInvalidView(state: MigrationTransferInvalidState) {
    BlankBgScaffold(
        containerColor = ZappTheme.colors.bg,
        topBar = {
            ZashiSmallTopAppBar(
                colors = zappTopAppBarColors(),
                navigationAction = { ZashiTopAppBarBackNavigation(onBack = state.onBack) },
            )
        }
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .scaffoldPadding(padding),
        ) {
            // Same layout for both causes (spec §6.2/§6.3), only the title/body/first step differ:
            // PLAN_UPDATE (§6.2) explains notes spent outside the migration flow invalidated the
            // plan; TRANSFER_EXPIRED (§6.3) explains transfer(s) expired without executing (the app
            // wasn't opened in time), naming the specific affected range.
            val (title, body, firstStep) =
                when (state.kind) {
                    MigrationAttentionKind.PLAN_UPDATE -> {
                        Triple(
                            stringRes(DesignR.string.migrationTransferInvalid_planUpdateTitle).getValue(),
                            stringRes(DesignR.string.migrationTransferInvalid_planUpdateBody).getValue(),
                            stringRes(DesignR.string.migrationTransferInvalid_planUpdateFirstStep).getValue(),
                        )
                    }

                    MigrationAttentionKind.TRANSFER_EXPIRED -> {
                        val isPlural = state.remainingCount > 1
                        Triple(
                            stringRes(DesignR.string.migrationTransferInvalid_transferExpiredTitle).getValue(),
                            stringRes(
                                if (isPlural) {
                                    DesignR.string.migrationTransferInvalid_transferExpiredBodyPlural
                                } else {
                                    DesignR.string.migrationTransferInvalid_transferExpiredBodySingular
                                },
                                state.invalidRange.getValue()
                            ).getValue(),
                            stringRes(
                                if (isPlural) {
                                    DesignR.string.migrationTransferInvalid_expiredStepLabelPlural
                                } else {
                                    DesignR.string.migrationTransferInvalid_expiredStepLabelSingular
                                }
                            ).getValue(),
                        )
                    }
                }
            Text(
                text = title,
                style = ZappTheme.typography.screenTitle,
                fontWeight = FontWeight.SemiBold,
                color = ZappTheme.colors.text,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = ZappTheme.typography.body,
                color = ZappTheme.colors.textMuted,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringRes(DesignR.string.migrationTransferInvalid_whatHappensNextTitle).getValue(),
                style = ZappTheme.typography.body,
                fontWeight = FontWeight.SemiBold,
                color = ZappTheme.colors.text,
            )
            Spacer(Modifier.height(12.dp))
            WhatHappensNextItem(
                number = 1,
                text = firstStep,
            )
            WhatHappensNextItem(
                number = 2,
                text = stringRes(DesignR.string.migrationTransferInvalid_remainingBalanceReproposed).getValue(),
            )
            WhatHappensNextItem(
                number = 3,
                text =
                    stringRes(
                        DesignR.string.migrationTransferInvalid_transfersDoneProgress,
                        state.completedCount,
                        state.totalCount
                    ).getValue(),
            )
            Spacer(Modifier.weight(1f))
            ZashiButton(
                state =
                    ButtonState(
                        text = stringRes(DesignR.string.migration_common_continue),
                        onClick = state.onContinue
                    ),
                modifier = Modifier.fillMaxWidth(),
                defaultPrimaryColors = zappAccentButtonColors(),
            )
        }
    }
}

@Composable
private fun WhatHappensNextItem(number: Int, text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .background(ZappTheme.colors.surface, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$number",
                style = ZappTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
                color = ZappTheme.colors.text,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = ZappTheme.typography.body,
            color = ZappTheme.colors.textMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewTransferExpired() =
    ZcashTheme {
        MigrationTransferInvalidView(
            state =
                MigrationTransferInvalidState(
                    kind = MigrationAttentionKind.TRANSFER_EXPIRED,
                    completedCount = 2,
                    totalCount = 5,
                    remainingCount = 3,
                    invalidRange = stringRes("3–5"),
                    onContinue = {},
                    onBack = {},
                )
        )
    }

@PreviewScreens
@Composable
private fun PreviewPlanUpdate() =
    ZcashTheme {
        MigrationTransferInvalidView(
            state =
                MigrationTransferInvalidState(
                    kind = MigrationAttentionKind.PLAN_UPDATE,
                    completedCount = 2,
                    totalCount = 5,
                    remainingCount = 1,
                    invalidRange = stringRes("3"),
                    onContinue = {},
                    onBack = {},
                )
        )
    }
