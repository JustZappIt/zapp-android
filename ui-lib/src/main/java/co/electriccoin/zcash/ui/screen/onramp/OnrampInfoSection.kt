// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.onramp

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
import xyz.justzappit.offramp.onramp.OnrampDestination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnrampInfoSheet(state: OnrampState, onDismiss: () -> Unit) {
    val isZcash = state.destination == OnrampDestination.ZCASH
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
                stringResource(R.string.onramp_info_title),
                style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
            )
            infoSteps(isZcash).forEachIndexed { index, step -> InfoStep(index + 1, stringResource(step)) }
            InfoNote(stringResource(if (isZcash) R.string.onramp_info_zcash_note else R.string.onramp_info_note))
            if (isZcash) InfoNote(stringResource(R.string.onramp_info_zcash_cost))
            OnrampDestinationInfo(state)
            ZappButton(
                text = stringResource(co.electriccoin.zcash.ui.design.R.string.general_ok),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun infoSteps(isZcash: Boolean): List<Int> =
    if (isZcash) {
        listOf(
            R.string.onramp_info_step_pay,
            R.string.onramp_info_zcash_step_settle,
            R.string.onramp_info_zcash_step_convert,
        )
    } else {
        listOf(
            R.string.onramp_info_step_pay,
            R.string.onramp_info_step_confirm,
            R.string.onramp_info_step_settle,
        )
    }

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
