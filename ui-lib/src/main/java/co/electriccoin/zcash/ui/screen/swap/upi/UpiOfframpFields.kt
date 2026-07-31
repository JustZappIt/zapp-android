package co.electriccoin.zcash.ui.screen.swap.upi

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextField
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextFieldDefaults
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@Composable
internal fun OfframpFieldLabel(text: String) {
    BasicText(
        text = text,
        style = ZappTheme.typography.eyebrow.copy(color = ZappTheme.colors.textMuted),
    )
}

/**
 * Bordered amount field with a leading token chip. Shared by the pay (INR) and top-up (USDC) screens.
 * [leadingHint] shows a muted secondary value inside the box on the left (e.g. the "≈ X USDC" estimate)
 * so the converted amount lives in the field itself rather than a line below it.
 */
@Composable
internal fun OfframpAmountField(
    tokenLabel: String,
    state: NumberTextFieldState,
    isError: Boolean = false,
    leadingHint: String? = null,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .border(BorderStroke(1.dp, if (isError) c.danger else c.accent))
                .padding(AMOUNT_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .background(c.surfaceAlt)
                    .padding(horizontal = TOKEN_PADDING_H.dp, vertical = TOKEN_PADDING_V.dp),
        ) {
            BasicText(
                text = tokenLabel,
                style = ZappTheme.typography.button.copy(color = c.text, fontWeight = FontWeight.SemiBold),
            )
        }
        Spacer(modifier = Modifier.width(TOKEN_GAP.dp))
        leadingHint?.let {
            BasicText(
                text = it,
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(TOKEN_GAP.dp))
        }
        ZashiNumberTextField(
            state = state,
            modifier = Modifier.weight(1f),
            textStyle =
                ZappTheme.typography.display.copy(
                    color = c.text,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                ),
            contentPadding = PaddingValues(horizontal = INNER_FIELD_PADDING.dp, vertical = INNER_FIELD_PADDING.dp),
            placeholder = {
                ZashiNumberTextFieldDefaults.Placeholder(
                    modifier = Modifier.fillMaxWidth(),
                    style = ZappTheme.typography.display,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    contentAlignment = Alignment.CenterEnd,
                )
            },
        )
    }
}

private const val AMOUNT_PADDING = 12
private const val TOKEN_PADDING_H = 12
private const val TOKEN_PADDING_V = 8
private const val TOKEN_GAP = 10
private const val INNER_FIELD_PADDING = 4
