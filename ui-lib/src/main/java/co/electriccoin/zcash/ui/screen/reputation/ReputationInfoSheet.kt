// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Where the mechanism is explained, so the screen itself can stay bare. Buying is a trade with a
 * stranger and the cap is the exchange's, not ours — both facts land here rather than as body
 * copy no one reads twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReputationInfoSheet(onDismiss: () -> Unit) {
    ZashiScreenModalBottomSheet(onDismissRequest = onDismiss) { padding ->
        Column(
            modifier =
                Modifier.padding(
                    start = SHEET_GUTTER.dp,
                    end = SHEET_GUTTER.dp,
                    bottom = padding.calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(INFO_GAP.dp),
        ) {
            BasicText(
                stringResource(R.string.reputation_info_title),
                style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
            )
            InfoParagraph(stringResource(R.string.reputation_info_trade))
            InfoParagraph(stringResource(R.string.reputation_info_cap))
            InfoNote(stringResource(R.string.reputation_info_privacy))
            InfoNote(stringResource(R.string.reputation_info_source))
            ZappButton(
                text = stringResource(co.electriccoin.zcash.ui.design.R.string.general_ok),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InfoParagraph(text: String) {
    BasicText(text, style = ZappTheme.typography.body.copy(color = ZappTheme.colors.text))
}

@Composable
private fun InfoNote(text: String) {
    BasicText(text, style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted))
}

private const val SHEET_GUTTER = 24
private const val INFO_GAP = 12
