package co.electriccoin.zcash.ui.screen.unifiedsend

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextField
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextFieldDefaults
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue

/**
 * The destination amount: one field carrying a single figure, denominated in the destination token or
 * in USD. The convert control flips between the two — the same affordance the pay side uses, so both
 * halves of the sentence behave alike. Editing it is what makes the payment exact-output.
 */
@Composable
internal fun SendDestinationRow(state: TheyReceiveState) {
    Row(verticalAlignment = CenterVertically) {
        Text(
            text = state.label.getValue(),
            style = ZappTheme.typography.caption,
            fontWeight = FontWeight.Medium,
            color = ZappTheme.colors.text,
        )
        Spacer(8.dp)
        ZashiNumberTextField(
            modifier = Modifier.weight(1f),
            state = state.amount,
            textStyle =
                ZappTheme.typography.caption.copy(
                    color = ZappTheme.colors.text,
                    fontWeight = FontWeight.Medium,
                ),
            placeholder = {
                ZashiNumberTextFieldDefaults.Placeholder(
                    style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textSubtle),
                    fontWeight = FontWeight.Normal,
                    text = "0.00"
                )
            },
            suffix = {
                Row(verticalAlignment = CenterVertically) {
                    Text(
                        text = state.unit,
                        style = ZappTheme.typography.caption,
                        fontWeight = FontWeight.Medium,
                        color = ZappTheme.colors.textMuted,
                    )
                    val onSwapCurrency = state.onSwapCurrency
                    if (onSwapCurrency != null) {
                        Image(
                            painter = painterResource(R.drawable.ic_send_convert),
                            contentDescription = stringResource(R.string.unified_send_swap_amounts),
                            colorFilter = ColorFilter.tint(color = ZappTheme.colors.textMuted),
                            modifier =
                                Modifier
                                    .size(28.dp)
                                    .clickable(onClick = onSwapCurrency)
                                    .padding(6.dp),
                        )
                    }
                }
            },
        )
    }
}
