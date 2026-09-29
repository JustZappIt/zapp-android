package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextField
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextFieldDefaults
import co.electriccoin.zcash.ui.design.component.ZashiTextFieldDefaults
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue

@Composable
fun ZappOfframpHeroAmountField(
    symbol: String,
    state: NumberTextFieldState,
    secondaryText: String?,
    modifier: Modifier = Modifier,
    balance: ZappFieldBalance? = null,
    isError: Boolean = false,
    flag: Painter? = null,
    leadingIcon: Painter? = null,
) {
    val c = ZappTheme.colors
    val amountStyle =
        if (leadingIcon != null &&
            state.innerState.innerTextFieldState.value
                .getValue()
                .length > COMPACT_AMOUNT_LENGTH
        ) {
            ZappTheme.typography.displaySecondary
        } else {
            ZappTheme.typography.display
        }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leadingIcon?.let {
                Image(
                    painter = it,
                    contentDescription = null,
                    modifier = Modifier.size(ZappTheme.spacing.xl4),
                )
                Spacer(Modifier.width(ZappTheme.spacing.lg))
            }
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
                    amountStyle.copy(
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
                    amountStyle.copy(
                        color = if (isError) c.danger else c.text,
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
                        errorTextColor = c.danger,
                        errorHintColor = c.textMuted,
                        errorBorderColor = c.bg,
                        errorContainerColor = c.bg,
                        errorPlaceholderColor = c.textSubtle,
                    ),
                placeholder = {
                    ZashiNumberTextFieldDefaults.Placeholder(
                        modifier = Modifier.fillMaxWidth(),
                        style = amountStyle,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Start,
                        contentAlignment = Alignment.CenterStart,
                    )
                },
            )
            balance?.let {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center,
                    modifier =
                        Modifier
                            .padding(start = 12.dp)
                            .then(
                                if (it.onClick != null) {
                                    Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .clickable(onClick = it.onClick)
                                        .semantics(mergeDescendants = true) { role = Role.Button }
                                } else {
                                    Modifier
                                }
                            ),
                ) {
                    BasicText(
                        text = it.label,
                        style = ZappTheme.typography.caption.copy(color = c.textSubtle, textAlign = TextAlign.End),
                        maxLines = 1,
                    )
                    BasicText(
                        text = it.amount,
                        style =
                            ZappTheme.typography.caption.copy(
                                color = if (it.onClick != null) c.accentText else c.textMuted,
                                fontWeight = if (it.onClick != null) FontWeight.SemiBold else FontWeight.Medium,
                                textAlign = TextAlign.End,
                            ),
                        maxLines = 1,
                    )
                }
            }
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

private const val COMPACT_AMOUNT_LENGTH = 8
