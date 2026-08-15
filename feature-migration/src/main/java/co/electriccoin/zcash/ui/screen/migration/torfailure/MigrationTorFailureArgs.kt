package co.electriccoin.zcash.ui.screen.migration.torfailure

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.zapp.ZappModalBottomSheetDragHandle
import co.electriccoin.zcash.ui.design.component.zapp.zappAccentButtonColors
import co.electriccoin.zcash.ui.design.component.zapp.zappDangerButtonColors
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import co.electriccoin.zcash.ui.design.R as DesignR

@Serializable
data object MigrationTorFailureArgs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationTorFailureScreen() {
    val vm = koinViewModel<MigrationTorFailureVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    MigrationTorFailureView(state)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationTorFailureView(
    state: MigrationTorFailureState?,
    sheetState: SheetState = rememberScreenModalBottomSheetState(),
) {
    ZashiScreenModalBottomSheet(
        state = state,
        sheetState = sheetState,
        containerColor = ZappTheme.colors.surface,
        dragHandle = { ZappModalBottomSheetDragHandle() },
    ) { innerState, contentPadding ->
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = contentPadding.calculateBottomPadding()),
        ) {
            Text(
                text = stringRes(DesignR.string.migrationTorFailure_title).getValue(),
                style = ZappTheme.typography.sectionTitle,
                fontWeight = FontWeight.SemiBold,
                color = ZappTheme.colors.text,
            )
            Spacer(4.dp)
            Text(
                text = stringRes(DesignR.string.migrationTorFailure_body).getValue(),
                style = ZappTheme.typography.body,
                color = ZappTheme.colors.textMuted,
            )
            Spacer(24.dp)
            RiskCard(
                title = stringRes(DesignR.string.migration_common_whatAreTheRisks).getValue(),
                body = stringRes(DesignR.string.migrationTorFailure_riskBody).getValue(),
            )
            Spacer(32.dp)
            ZashiButton(
                state =
                    ButtonState(
                        text = stringRes(DesignR.string.migration_common_continueWithoutTor),
                        onClick = innerState.onContinueWithoutTor
                    ),
                modifier = Modifier.fillMaxWidth(),
                defaultPrimaryColors = zappDangerButtonColors(),
            )
            Spacer(8.dp)
            ZashiButton(
                state =
                    ButtonState(
                        text = stringRes(DesignR.string.migration_common_tryAgain),
                        onClick = innerState.onTryAgain
                    ),
                modifier = Modifier.fillMaxWidth(),
                defaultPrimaryColors = zappAccentButtonColors(),
            )
        }
    }
}

@Composable
private fun RiskCard(title: String, body: String) {
    Surface(
        color = ZappTheme.colors.surfaceAlt,
        border = BorderStroke(1.dp, ZappTheme.colors.borderStrong),
        shape = RectangleShape,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = ZappTheme.typography.rowTitle,
                fontWeight = FontWeight.SemiBold,
                color = ZappTheme.colors.text,
            )
            Spacer(2.dp)
            Text(
                text = body,
                style = ZappTheme.typography.caption,
                color = ZappTheme.colors.textMuted,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        MigrationTorFailureView(
            state =
                MigrationTorFailureState(
                    onContinueWithoutTor = {},
                    onTryAgain = {},
                    onBack = {},
                )
        )
    }
