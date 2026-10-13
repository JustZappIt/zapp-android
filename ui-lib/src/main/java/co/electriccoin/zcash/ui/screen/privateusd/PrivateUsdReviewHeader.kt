// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** The top of a conversion's review: what's paid, for about what's received. */
@Composable
internal fun PrivateUsdReviewHeader(
    pay: String,
    receive: String,
) {
    val c = ZappTheme.colors
    BasicText(
        text = stringResource(R.string.convert_review_title),
        style = ZappTheme.typography.sectionTitle.copy(color = c.text),
        modifier = Modifier.semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs)) {
        BasicText(text = pay, style = ZappTheme.typography.display.copy(color = c.text))
        BasicText(
            text = stringResource(R.string.convert_review_arrow),
            style = ZappTheme.typography.caption.copy(color = c.textMuted),
        )
        BasicText(text = receive, style = ZappTheme.typography.balanceDisplay.copy(color = c.accent))
    }
}
