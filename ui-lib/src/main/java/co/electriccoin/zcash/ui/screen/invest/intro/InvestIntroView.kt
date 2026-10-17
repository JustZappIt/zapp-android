package co.electriccoin.zcash.ui.screen.invest.intro

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_LG
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_MD
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_SM
import co.electriccoin.zcash.ui.screen.invest.common.InvestNotice
import co.electriccoin.zcash.ui.screen.invest.common.InvestScreenFrame

@Composable
internal fun InvestIntroView(state: InvestIntroState) {
    val c = ZappTheme.colors
    InvestScreenFrame(
        title = stringResource(R.string.invest_intro_title),
        onBack = state.onBack,
        primaryButton = state.primaryButton,
        isPrimaryLoading = state.isSettingUp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(INVEST_GAP_LG.dp)) {
            FACTS.forEachIndexed { index, (title, body) -> Fact(index + 1, title, body) }
        }
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
        InvestNotice(body = stringResource(R.string.invest_intro_recovery))
        Spacer(Modifier.height(INVEST_GAP_MD.dp))
        BasicText(text = state.hint.getValue(), style = ZappTheme.typography.caption.copy(color = c.textMuted))
        state.errorText?.let {
            Spacer(Modifier.height(INVEST_GAP_SM.dp))
            BasicText(
                text = it.getValue(),
                style = ZappTheme.typography.caption.copy(color = c.danger, fontWeight = FontWeight.Medium),
            )
        }
    }
}

@Composable
private fun Fact(
    number: Int,
    @StringRes title: Int,
    @StringRes body: Int,
) {
    val c = ZappTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(INVEST_GAP_MD.dp)) {
        Box(
            modifier = Modifier.size(NUMBER_BOX.dp).background(c.accentSoft, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = number.toString(),
                style = ZappTheme.typography.rowTitle.copy(color = c.accentText, fontWeight = FontWeight.Bold),
            )
        }
        Column(Modifier.weight(1f)) {
            BasicText(text = stringResource(title), style = ZappTheme.typography.rowTitle.copy(color = c.text))
            Spacer(Modifier.height(2.dp))
            BasicText(text = stringResource(body), style = ZappTheme.typography.body.copy(color = c.textMuted))
        }
    }
}

private const val NUMBER_BOX = 28

private val FACTS =
    listOf(
        R.string.invest_intro_fact1_title to R.string.invest_intro_fact1_body,
        R.string.invest_intro_fact2_title to R.string.invest_intro_fact2_body,
        R.string.invest_intro_fact3_title to R.string.invest_intro_fact3_body,
    )

@PreviewScreens
@Composable
private fun PreviewIntro() {
    ZcashTheme {
        InvestIntroView(
            InvestIntroState(
                primaryButton = ButtonState(stringRes("Set up Invest")),
                isSettingUp = false,
                hint = stringRes("Uses Tor · takes a few seconds"),
                errorText = null,
                onBack = {},
            ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewIntroError() {
    ZcashTheme {
        InvestIntroView(
            InvestIntroState(
                primaryButton = ButtonState(stringRes("Set up Invest")),
                isSettingUp = false,
                hint = stringRes("Takes a few seconds"),
                errorText = stringRes("Check your phone's date and time, then try again."),
                onBack = {},
            ),
        )
    }
}
