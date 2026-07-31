// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.chat.identity.ChatIdentitySetupState

@Composable
internal fun ChatIdentitySetupView(
    state: ChatIdentitySetupState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = ZappTheme.colors.accent,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = state.title.getValue(),
            style = ZappTheme.typography.displaySecondary,
            color = ZappTheme.colors.text,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = state.subtitle.getValue(),
            style = ZappTheme.typography.body,
            color = ZappTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        PlainTextField(
            value = state.displayName,
            placeholder = state.displayNamePlaceholder.getValue(),
            onValueChange = state.onDisplayNameChange,
        )

        Spacer(modifier = Modifier.height(20.dp))

        PrimarySubmitButton(
            label = state.submitLabel.getValue(),
            isSubmitting = state.isSubmitting,
            onClick = state.onSubmit,
        )

        state.error?.let { error ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = error.getValue(),
                style = ZappTheme.typography.caption,
                color = ZappTheme.colors.danger,
            )
            state.diagnostic?.let { diagnostic ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.chat_identity_setup_support_hint),
                    style = ZappTheme.typography.caption,
                    color = ZappTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val context = LocalContext.current
                val copyLabel = stringResource(R.string.chat_identity_setup_copy_details)
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText(copyLabel, diagnostic))
                    },
                    shape = RectangleShape,
                ) {
                    Text(copyLabel, style = ZappTheme.typography.button)
                }
            }
        }
    }
}

@Composable
private fun PlainTextField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        colors =
            TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
    )
}

@Composable
private fun PrimarySubmitButton(
    label: String,
    isSubmitting: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isSubmitting,
        shape = RectangleShape,
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = ZappTheme.colors.onAccent,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(label, modifier = Modifier.padding(vertical = 4.dp), style = ZappTheme.typography.button)
    }
}
