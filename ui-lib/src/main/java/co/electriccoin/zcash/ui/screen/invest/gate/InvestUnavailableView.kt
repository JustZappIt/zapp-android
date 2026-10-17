package co.electriccoin.zcash.ui.screen.invest.gate

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.INVEST_GAP_LG
import co.electriccoin.zcash.ui.screen.invest.common.InvestScreenFrame

@Composable
internal fun InvestUnavailableView(state: InvestUnavailableState) {
    InvestScreenFrame(
        title = stringResource(R.string.invest_unavailable_title),
        onBack = state.onDone,
        primaryButton = ButtonState(stringRes(R.string.invest_back_to_pay), onClick = state.onDone),
    ) {
        BasicText(
            text = state.body.getValue(),
            style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
        )
        Spacer(Modifier.height(INVEST_GAP_LG.dp))
        ZappButton(
            text = stringResource(R.string.invest_unavailable_change),
            variant = ZappButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            onClick = state.onChangeCountry,
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewUnavailable() {
    ZcashTheme {
        InvestUnavailableView(
            InvestUnavailableState(
                body = stringRes("Tokenised stocks can't be offered to people who live in Canada."),
                onChangeCountry = {},
                onDone = {},
            ),
        )
    }
}
