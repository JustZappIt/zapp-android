package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextField
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextFieldDefaults
import co.electriccoin.zcash.ui.design.component.ZashiTextFieldDefaults
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@Composable
fun ZappOfframpHeroAmountField(
    symbol: String,
    state: NumberTextFieldState,
    secondaryText: String?,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    flag: Painter? = null,
) {
    val c = ZappTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            flag?.let {
                Image(
                    painter = it,
                    contentDescription = null,
                    modifier = Modifier.size(width = 30.dp, height = 20.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            BasicText(
                text = symbol,
                style =
                    ZappTheme.typography.display.copy(
                        color = c.text,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            ZashiNumberTextField(
                state = state,
                modifier = Modifier.weight(1f),
                shape = RectangleShape,
                textStyle =
                    ZappTheme.typography.display.copy(
                        color = c.text,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Start,
                    ),
                contentPadding = PaddingValues(vertical = 4.dp),
                colors =
                    ZashiTextFieldDefaults.defaultColors(
                        textColor = c.text,
                        hintColor = c.textMuted,
                        borderColor = c.bg,
                        focusedBorderColor = c.bg,
                        containerColor = c.bg,
                        focusedContainerColor = c.bg,
                        placeholderColor = c.textSubtle,
                        disabledTextColor = c.textMuted,
                        disabledHintColor = c.textMuted,
                        disabledBorderColor = c.bg,
                        disabledContainerColor = c.bg,
                        disabledPlaceholderColor = c.textSubtle,
                        errorTextColor = c.text,
                        errorHintColor = c.textMuted,
                        errorBorderColor = c.bg,
                        errorContainerColor = c.bg,
                        errorPlaceholderColor = c.textSubtle,
                    ),
                placeholder = {
                    ZashiNumberTextFieldDefaults.Placeholder(
                        modifier = Modifier.fillMaxWidth(),
                        style = ZappTheme.typography.display,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Start,
                        contentAlignment = Alignment.CenterStart,
                    )
                },
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(if (isError) c.danger else c.accent, RectangleShape),
        )
        secondaryText?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = it,
                style = ZappTheme.typography.body.copy(color = c.textMuted),
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}
