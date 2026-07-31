package co.electriccoin.zcash.ui.screen.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ZashiDisclaimerState
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.onboarding.view.OnbBottomDock
import co.electriccoin.zcash.ui.screen.onboarding.view.OnbBulletRow
import co.electriccoin.zcash.ui.screen.onboarding.view.OnbHero
import co.electriccoin.zcash.ui.screen.onboarding.view.OnbSub

@Composable
internal fun KeepOpenView(state: KeepOpenState) {
    val c = ZappTheme.colors

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(38.dp))

            OnbHero(text = state.title.getValue())

            Spacer(Modifier.height(16.dp))

            OnbSub(
                text = state.subtitle.getValue(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OnbSub(
                text = state.description.getValue(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))

            OnbBulletRow(label = state.bullet1.getValue(), isFirst = true)
            OnbBulletRow(label = state.bullet2.getValue())

            Spacer(Modifier.height(20.dp))

            DisclaimerCard(state = state.disclaimer)

            Spacer(Modifier.height(20.dp))

            KeepScreenOnCheckboxRow(
                label = state.checkboxLabel.getValue(),
                isChecked = state.isChecked,
                onClick = { state.onCheckedChange(!state.isChecked) },
            )

            Spacer(Modifier.height(16.dp))
        }

        OnbBottomDock(
            cta = state.button.text.getValue(),
            onCta = state.button.onClick,
            ctaEnabled = state.button.isEnabled,
        )
    }
}

@Composable
private fun DisclaimerCard(state: ZashiDisclaimerState) {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surface, RectangleShape)
                .border(1.dp, c.border, RectangleShape)
                .padding(14.dp),
    ) {
        BasicText(
            text = state.value.getValue(),
            style =
                ZappTheme.typography.body.copy(
                    color = c.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                ),
        )
    }
}

@Composable
private fun KeepScreenOnCheckboxRow(
    label: String,
    isChecked: Boolean,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .background(if (isChecked) c.accent else c.bg, RectangleShape)
                        .border(2.dp, if (isChecked) c.accent else c.borderStrong, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isChecked) {
                    BasicText(
                        text = "✓",
                        style =
                            ZappTheme.typography.button.copy(
                                color = c.onAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        BasicText(
            text = label,
            style =
                ZappTheme.typography.body.copy(
                    color = c.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun KeepOpenViewPreview() =
    ZcashTheme {
        KeepOpenView(
            KeepOpenState(
                description = stringRes(R.string.keep_open_restore_description),
                subtitle = stringRes(R.string.keep_open_restore_subtitle),
                disclaimer = ZashiDisclaimerState.warning(stringRes(R.string.keep_open_keystone_warning)),
                checkboxLabel = stringRes(R.string.keep_open_restore_checkbox),
                isChecked = true,
                onCheckedChange = { },
                button =
                    ButtonState(
                        text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_got_it),
                        onClick = { },
                    ),
            )
        )
    }
