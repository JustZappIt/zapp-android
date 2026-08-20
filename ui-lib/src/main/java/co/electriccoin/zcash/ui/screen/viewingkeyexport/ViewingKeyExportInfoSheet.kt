// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.viewingkeyexport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * The detail the export screen deliberately does not carry. Someone exporting a viewing key
 * usually knows what one is, so the screen stays a choice between two options and the
 * explanation of what each reveals lives one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewingKeyExportInfoSheet(onDismiss: () -> Unit) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    ZashiScreenModalBottomSheet(onDismissRequest = onDismiss) { padding ->
        Column(
            modifier =
                Modifier.padding(
                    start = spacing.xl3,
                    end = spacing.xl3,
                    bottom = padding.calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            BasicText(
                text = stringResource(R.string.viewing_key_export_info_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            BasicText(
                text = stringResource(R.string.viewing_key_export_info_intro),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            InfoTopic(
                title = stringResource(R.string.viewing_key_export_ufvk_title),
                body = stringResource(R.string.viewing_key_export_info_ufvk_body),
            )
            InfoTopic(
                title = stringResource(R.string.viewing_key_export_uivk_title),
                body = stringResource(R.string.viewing_key_export_info_uivk_body),
            )
            InfoTopic(
                title = stringResource(R.string.viewing_key_export_info_irrevocable_title),
                body = stringResource(R.string.viewing_key_export_info_irrevocable_body),
            )
            BasicText(
                text = stringResource(R.string.viewing_key_export_info_compatibility),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
            ZappButton(
                text = stringResource(co.electriccoin.zcash.ui.design.R.string.general_ok),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InfoTopic(
    title: String,
    body: String,
) {
    val c = ZappTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs)) {
        BasicText(
            text = title,
            style = ZappTheme.typography.rowTitle.copy(color = c.text),
        )
        BasicText(
            text = body,
            style = ZappTheme.typography.body.copy(color = c.textMuted),
        )
    }
}
