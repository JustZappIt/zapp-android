// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.R as DesignR

/**
 * The header every voting screen wears. Upstream carries back (and sometimes close) navigation in
 * this bar; the fork puts back at the bottom-left of the screen instead, so the bar is title-only
 * plus the optional poll-source gear. Screens pair it with `ZappBottomActionBar(onBack = ...)`.
 */
@Composable
fun VoteAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onConfigSettings: (() -> Unit)? = null,
) {
    ZappScreenHeader(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        right =
            onConfigSettings?.let {
                {
                    val c = ZappTheme.colors
                    val description = stringResource(R.string.coinVote_pollsList_chainConfigAccessibility)
                    Box(
                        modifier = Modifier.size(TOUCH_TARGET).clickable(onClick = it),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(DesignR.drawable.ic_app_bar_settings),
                            contentDescription = description,
                            tint = c.text,
                            modifier = Modifier.size(ICON)
                        )
                    }
                }
            }
    )
}

private val TOUCH_TARGET = 48.dp
private val ICON = 20.dp
