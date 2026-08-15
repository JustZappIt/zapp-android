package co.electriccoin.zcash.ui.screen.migration.privacy

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.zapp.ZappModalBottomSheetDragHandle
import co.electriccoin.zcash.ui.design.component.zapp.zappAccentButtonColors
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiLightColors
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import co.electriccoin.zcash.ui.design.R as DesignR

@Serializable
data class MigrationPrivacyArgs(
    val mode: MigrationMode
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationPrivacyScreen(args: MigrationPrivacyArgs) {
    val vm = koinViewModel<MigrationPrivacyVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    MigrationPrivacyView(state)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationPrivacyView(
    state: MigrationPrivacyState?,
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
            Image(
                painter = painterResource(co.electriccoin.zcash.ui.R.drawable.ic_tor_settings),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            Spacer(16.dp)
            Text(
                text = stringRes(DesignR.string.migration_common_enableTorProtectionTitle).getValue(),
                style = ZappTheme.typography.sectionTitle,
                fontWeight = FontWeight.SemiBold,
                color = ZappTheme.colors.text,
            )
            Spacer(4.dp)
            Text(
                text = innerState.body.getValue(),
                style = ZappTheme.typography.body,
                color = ZappTheme.colors.textMuted,
            )
            Spacer(32.dp)
            TorToggleCard(innerState.checkbox)
            Spacer(32.dp)
            ZashiButton(
                state =
                    ButtonState(
                        text = stringRes(DesignR.string.migration_common_gotIt),
                        onClick = innerState.onConfirm
                    ),
                modifier = Modifier.fillMaxWidth(),
                defaultPrimaryColors = zappAccentButtonColors(),
            )
        }
    }
}

// Hand-styled pill switch (Material3's default Switch renders solid black-on-black in this app's
// theme). Based on RestoreTorView.kt's toggle, but per the migration Figma the thumb is a
// theme-independent white and the card shows no border highlight in EITHER state (MOB-1620: the
// OFF state was still showing one after the ON-state border was removed). If the two Tor toggles
// must stay identical, mirror these two tweaks in RestoreTorView.kt as well.
@Suppress("MagicNumber")
@Composable
private fun TorToggleCard(state: CheckboxState) {
    Surface(
        color = ZappTheme.colors.surfaceAlt,
        border = BorderStroke(1.dp, Color.Transparent),
        shape = RectangleShape,
        onClick = state.onClick,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.getValue(),
                    style = ZappTheme.typography.rowTitle,
                    fontWeight = FontWeight.SemiBold,
                    color = ZappTheme.colors.text,
                )
                state.subtitle?.let {
                    Spacer(2.dp)
                    Text(
                        text = it.getValue(),
                        style = ZappTheme.typography.caption,
                        color = ZappTheme.colors.textMuted,
                    )
                }
            }
            Spacer(20.dp)
            val switchColor by animateColorAsState(
                if (state.isChecked) {
                    ZappTheme.colors.success
                } else {
                    ZappTheme.colors.border
                }
            )
            val offset by animateDpAsState(if (state.isChecked) 21.dp else 0.dp)
            Surface(
                modifier =
                    Modifier
                        .width(64.dp)
                        .height(28.dp),
                color = switchColor,
                shape = RectangleShape,
            ) {
                Box(modifier = Modifier.padding(2.dp)) {
                    Box(
                        modifier =
                            Modifier
                                .offset(x = offset)
                                .width(39.dp)
                                .height(24.dp)
                                .clip(RectangleShape)
                                .background(ZashiLightColors.Surfaces.bgPrimary)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        MigrationPrivacyView(
            state =
                MigrationPrivacyState(
                    body = stringRes(DesignR.string.migrationPrivacy_bodyAutomatic),
                    checkbox =
                        CheckboxState(
                            title = stringRes(DesignR.string.migration_common_enableTorProtectionTitle),
                            subtitle = stringRes(DesignR.string.migration_common_torCheckboxSubtitle),
                            isChecked = true,
                            onClick = {},
                        ),
                    onConfirm = {},
                    onBack = {},
                )
        )
    }
