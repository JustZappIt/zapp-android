// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappInfoButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappInfoSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue

/** The frame of every private USD screen: a header with its "i", the content and any error, then back and an action. */
@Composable
internal fun PrivateUsdScaffold(
    title: String,
    info: PrivateUsdInfo,
    onBack: () -> Unit,
    layout: PrivateUsdLayout = PrivateUsdLayout.STANDARD,
    primaryButton: ButtonState? = null,
    isBackEnabled: Boolean = true,
    error: StringResource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PrivateUsdFrame(title, subtitle = null, info, onBack, layout, primaryButton, isBackEnabled) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal =
                            when (layout) {
                                PrivateUsdLayout.STANDARD -> ZappTheme.spacing.gutter
                                PrivateUsdLayout.AMOUNT_ENTRY -> AMOUNT_ENTRY_GUTTER
                            },
                        vertical =
                            when (layout) {
                                PrivateUsdLayout.STANDARD -> ZappTheme.spacing.xl
                                PrivateUsdLayout.AMOUNT_ENTRY -> ZappTheme.spacing.md
                            },
                    ),
            verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xl2),
        ) {
            content()
            error?.let {
                BasicText(
                    text = it.getValue(),
                    style =
                        ZappTheme.typography.caption.copy(
                            color = ZappTheme.colors.danger,
                            fontWeight = FontWeight.Medium,
                        ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** [PrivateUsdScaffold] for a screen holding a list as long as its history: its items lay themselves out. */
@Composable
internal fun PrivateUsdLazyScaffold(
    title: String,
    subtitle: String,
    info: PrivateUsdInfo,
    onBack: () -> Unit,
    primaryButton: ButtonState,
    content: LazyListScope.() -> Unit,
) {
    PrivateUsdFrame(title, subtitle, info, onBack, PrivateUsdLayout.STANDARD, primaryButton, isBackEnabled = true) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = ZappTheme.spacing.xl),
            content = content,
        )
    }
}

@Composable
private fun PrivateUsdFrame(
    title: String,
    subtitle: String?,
    info: PrivateUsdInfo,
    onBack: () -> Unit,
    layout: PrivateUsdLayout,
    primaryButton: ButtonState?,
    isBackEnabled: Boolean,
    body: @Composable ColumnScope.() -> Unit,
) {
    var showsInfo by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ZappTheme.colors.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .imePadding(),
    ) {
        ZappScreenHeader(
            title = title,
            subtitle = subtitle,
            titleStyle =
                when (layout) {
                    PrivateUsdLayout.STANDARD -> ZappTheme.typography.screenTitle
                    PrivateUsdLayout.AMOUNT_ENTRY -> ZappTheme.typography.displaySecondary
                },
            right = {
                ZappInfoButton(
                    contentDescription = stringResource(R.string.private_usd_info_content_description),
                    onClick = { showsInfo = true },
                )
            },
        )
        body()
        ZappBottomActionBar(
            onBack = onBack,
            isBackEnabled = isBackEnabled,
            style = layout.actionBar,
            primaryAction = primaryButton?.let { button -> { PrimaryButton(button) } },
        )
    }
    if (showsInfo) {
        ZappInfoSheet(
            title = info.title.getValue(),
            steps = info.steps.map { it.getValue() },
            notes = info.notes.map { it.getValue() },
            onDismiss = { showsInfo = false },
            titleDescription = info.titleDescription?.getValue(),
        )
    }
}

@Composable
private fun RowScope.PrimaryButton(button: ButtonState) {
    ZappButton(
        text = button.text.getValue(),
        enabled = button.isEnabled,
        loading = button.isLoading,
        modifier = Modifier.weight(1f).padding(start = ZappTheme.spacing.lg),
        onClick = button.onClick,
    )
}

// The wallet send's own margin, so the two amount screens line up.
private val AMOUNT_ENTRY_GUTTER = 28.dp
