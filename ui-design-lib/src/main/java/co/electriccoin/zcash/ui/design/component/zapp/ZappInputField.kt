package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** Fixed so a field with a 48dp trailing action is the same height as one without. */
val ZAPP_INPUT_FIELD_HEIGHT = 52.dp

@Composable
fun ZappInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val c = ZappTheme.colors
    val isFilled = value.text.isNotEmpty()
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ZAPP_INPUT_FIELD_HEIGHT)
                .background(c.surfaceInput, RectangleShape)
                .then(
                    if (isFilled) {
                        Modifier.border(BorderStroke(2.dp, c.borderStrong), RectangleShape)
                    } else {
                        Modifier.border(BorderStroke(1.dp, c.border), RectangleShape)
                    }
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, end = if (trailingIcon == null) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let {
                it()
                Spacer(Modifier.width(10.dp))
            }
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    textStyle = ZappTheme.typography.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (value.text.isEmpty()) {
                    BasicText(
                        text = placeholder,
                        style = ZappTheme.typography.body.copy(color = c.textSubtle),
                    )
                }
            }
            trailingIcon?.let { it() }
        }
    }
}
