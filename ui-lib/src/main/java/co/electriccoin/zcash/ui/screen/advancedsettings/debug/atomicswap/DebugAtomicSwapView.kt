// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.atomicswap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme

@Composable
internal fun DebugAtomicSwapView(state: DebugAtomicSwapState) {
    val c = ZappTheme.colors
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
                    .padding(start = PADDING.dp, end = PADDING.dp, bottom = BACK_DOCK_CLEARANCE.dp),
            verticalArrangement = Arrangement.spacedBy(GAP.dp),
        ) {
            ZappScreenHeader(title = "Swap refund (spike)")
            Section("Status", state.status)
            state.depositAddress?.let { address ->
                Section("Deposit address (send testnet ZEC here)", listOf(address), mono = true)
                ZappButton(text = "Copy address", variant = ZappButtonVariant.Secondary, onClick = state.onCopyAddress)
            }
            Step("1. Prepare: address and birthday", state.isBusy, state.onPrepare)
            Step("2. Import the deposit account", state.isBusy, state.onImport)
            Step("3. Sweep it home", state.isBusy, state.onSweep)
            Step("4. Delete the deposit account", state.isBusy, state.onDelete)
            if (state.activity.isNotEmpty()) Section("Activity", state.activity, mono = true)
            state.error?.let { error ->
                BasicText(text = error, style = ZappTheme.typography.body.copy(color = c.danger))
            }
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = PADDING.dp, bottom = GAP.dp)
                    .background(c.surface, RectangleShape)
                    .border(BorderStroke(1.dp, c.accent), RectangleShape),
        ) {
            ZappBackButton(onClick = state.onBack)
        }
    }
}

@Composable
private fun Section(
    title: String,
    lines: List<String>,
    mono: Boolean = false,
) {
    val c = ZappTheme.colors
    ZappBorderedCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LINE_GAP.dp)) {
        BasicText(text = title, style = ZappTheme.typography.eyebrow.copy(color = c.textMuted))
        val style = if (mono) ZappTheme.typography.mono else ZappTheme.typography.body
        lines.forEach { BasicText(text = it, style = style.copy(color = c.text)) }
    }
}

@Composable
private fun Step(
    text: String,
    isBusy: Boolean,
    onClick: () -> Unit,
) = ZappButton(
    text = text,
    modifier = Modifier.fillMaxWidth(),
    variant = ZappButtonVariant.Secondary,
    enabled = !isBusy,
    onClick = onClick
)

private const val PADDING = 20
private const val GAP = 12
private const val LINE_GAP = 4
private const val BACK_DOCK_CLEARANCE = 96

@PreviewScreens
@Composable
private fun DebugAtomicSwapPreview() =
    ZcashTheme {
        DebugAtomicSwapView(
            state =
                DebugAtomicSwapState(
                    status = listOf("birthday: 4134500", "deposit account imported: yes", "deposit: available 0.01"),
                    depositAddress = "utest1exuj2qh9gcll0zjygvk7c48e5ra40wwtvdgd2u0ygwn2g3kanp47utpgh25m4pqwcj…",
                    onCopyAddress = {},
                    activity = listOf("prepare: deposit address ready; birthday 4134500"),
                    error = null,
                    isBusy = false,
                    onPrepare = {},
                    onImport = {},
                    onSweep = {},
                    onDelete = {},
                    onBack = {},
                ),
        )
    }
