// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** A debug screen: its sections in a scrolling column under [title], with back docked bottom-left. */
@Composable
internal fun DebugFrame(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = spacing.xl2, end = spacing.xl2, bottom = spacing.xl6 * 2),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            ZappScreenHeader(title = title)
            content()
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = spacing.xl2, bottom = spacing.lg)
                    .background(c.surface, RectangleShape)
                    .border(BorderStroke(1.dp, c.accent), RectangleShape),
        ) {
            ZappBackButton(onClick = onBack)
        }
    }
}

@Composable
internal fun DebugSection(
    title: String,
    lines: List<String>,
    mono: Boolean = false,
) {
    val c = ZappTheme.colors
    ZappBorderedCard(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xs),
    ) {
        BasicText(text = title, style = ZappTheme.typography.eyebrow.copy(color = c.textMuted))
        val style = if (mono) ZappTheme.typography.mono else ZappTheme.typography.body
        lines.forEach { BasicText(text = it, style = style.copy(color = c.text)) }
    }
}

@Composable
internal fun DebugAction(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = ZappButton(
    text = text,
    modifier = Modifier.fillMaxWidth(),
    variant = ZappButtonVariant.Secondary,
    enabled = enabled,
    onClick = onClick,
)

@Composable
internal fun DebugError(error: String) =
    BasicText(text = error, style = ZappTheme.typography.body.copy(color = ZappTheme.colors.danger))
