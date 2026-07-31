package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme

enum class TwoFAMode { Bio, Pin }

@Composable
internal fun TwoFAChoiceScreen(
    onPick: (TwoFAMode) -> Unit,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 20.dp)) {
            OnbProgress(step = 3)
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, top = 24.dp),
        ) {
            GhostNum(n = 3, modifier = Modifier.align(Alignment.TopEnd))
            Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                Eyebrow(stringResource(R.string.onboarding_secure_badge))
                Spacer(Modifier.height(14.dp))
                OnbHero(text = stringResource(R.string.onboarding_secure_title))
                Spacer(Modifier.height(14.dp))
                OnbSub(stringResource(R.string.onboarding_secure_subtitle))
            }
            OnbActionListCard(
                actions =
                    listOf(
                        OnbAction(
                            icon = "◎",
                            label = stringResource(R.string.onboarding_secure_bio_label),
                            sub = stringResource(R.string.onboarding_secure_bio_sub),
                            onClick = { onPick(TwoFAMode.Bio) },
                            highlight = true,
                        ),
                        OnbAction(
                            icon = "✱",
                            label = stringResource(R.string.security_settings_tab_pin),
                            sub = stringResource(R.string.onboarding_secure_pin_sub),
                            onClick = { onPick(TwoFAMode.Pin) },
                        ),
                    ),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
            )
        }
        OnbBottomDock(
            cta = "",
            onCta = {},
            showBack = false,
            showCta = false,
            noBorder = true,
        )
    }
}
