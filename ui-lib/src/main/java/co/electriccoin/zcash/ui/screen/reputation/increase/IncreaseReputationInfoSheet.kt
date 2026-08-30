// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * The four things a user needs to know before spending five minutes in another app: what Reclaim
 * is, that we pay the fee, that older accounts only, and that a verification is spent once and for
 * good — including against a wallet recovered from a new seed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IncreaseReputationInfoSheet(onDismiss: () -> Unit) {
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
                stringResource(R.string.increase_reputation_info_title),
                style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
            )
            INFO_STEPS.forEachIndexed { index, step -> InfoStep(index + 1, stringResource(step)) }
            InfoNote(stringResource(R.string.increase_reputation_info_gas))
            InfoNote(stringResource(R.string.increase_reputation_info_time))
            InfoNote(stringResource(R.string.increase_reputation_info_age))
            InfoNote(stringResource(R.string.increase_reputation_info_once))
            ZappButton(
                text = stringResource(co.electriccoin.zcash.ui.design.R.string.general_ok),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val INFO_STEPS =
    listOf(
        R.string.increase_reputation_info_step_pick,
        R.string.increase_reputation_info_step_open,
        R.string.increase_reputation_info_step_sign_in,
        R.string.increase_reputation_info_step_proof,
    )

@Composable
private fun InfoStep(index: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(INFO_GAP.dp)) {
        Box(
            modifier = Modifier.size(STEP_BADGE.dp).background(ZappTheme.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                index.toString(),
                style =
                    ZappTheme.typography.caption.copy(
                        color = ZappTheme.colors.accentText,
                        fontWeight = FontWeight.Black,
                    ),
            )
        }
        BasicText(text, style = ZappTheme.typography.body.copy(color = ZappTheme.colors.text))
    }
}

@Composable
private fun InfoNote(text: String) {
    BasicText(text, style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted))
}

private const val SHEET_GUTTER = 24
private const val INFO_GAP = 12
private const val STEP_BADGE = 20
