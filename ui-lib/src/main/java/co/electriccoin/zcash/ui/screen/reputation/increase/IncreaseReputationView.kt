// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.reputation.increase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappCompactButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappDoneButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettingsGroup
import co.electriccoin.zcash.ui.design.component.zapp.ZappStepList
import co.electriccoin.zcash.ui.design.component.zapp.ZappSuccessHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.reputation.REPUTATION_BOTTOM_BAR_GAP
import co.electriccoin.zcash.ui.screen.reputation.REPUTATION_HORIZONTAL_PADDING
import co.electriccoin.zcash.ui.screen.reputation.REPUTATION_INFO_TAP_TARGET
import co.electriccoin.zcash.ui.screen.reputation.REPUTATION_NOTICE_PADDING
import co.electriccoin.zcash.ui.screen.reputation.REPUTATION_SECTION_GAP
import co.electriccoin.zcash.ui.screen.reputation.REPUTATION_VERTICAL_PADDING
import co.electriccoin.zcash.ui.screen.reputation.ReputationNotice

@Composable
internal fun IncreaseReputationView(state: IncreaseReputationState) {
    val c = ZappTheme.colors
    val infoContentDescription = stringResource(R.string.increase_reputation_info_content_description)
    var showInfo by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val uriHandler = LocalUriHandler.current
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .imePadding(),
    ) {
        ZappScreenHeader(
            title = stringResource(R.string.increase_reputation_title),
            right = {
                Box(
                    modifier =
                        Modifier
                            .size(REPUTATION_INFO_TAP_TARGET)
                            .clickable { showInfo = true }
                            .semantics {
                                role = Role.Button
                                contentDescription = infoContentDescription
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = c.text)
                }
            },
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = REPUTATION_HORIZONTAL_PADDING, vertical = REPUTATION_VERTICAL_PADDING),
        ) {
            when {
                state.run != null -> RunContent(state.run, state)
                state.isLoading -> LoadingContent()
                else -> ListContent(state)
            }
        }
        BottomDock(state, uriHandler)
    }
    if (showInfo) IncreaseReputationInfoSheet { showInfo = false }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = ZappTheme.colors.accent)
    }
}

@Composable
private fun ListContent(state: IncreaseReputationState) {
    Column(verticalArrangement = Arrangement.spacedBy(REPUTATION_SECTION_GAP)) {
        BasicText(
            text = stringResource(R.string.increase_reputation_intro),
            style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
        )
        state.error?.let {
            BasicText(
                text = it.getValue(),
                style = ZappTheme.typography.body.copy(color = ZappTheme.colors.danger),
            )
        }
        if (state.platforms.isNotEmpty()) {
            ZappSettingsGroup(title = stringResource(R.string.increase_reputation_group)) {
                state.platforms.forEachIndexed { index, row ->
                    if (index > 0) ZappRowDivider()
                    PlatformRow(row)
                }
            }
        }
    }
}

@Composable
private fun PlatformRow(row: VerifiableRow) {
    val c = ZappTheme.colors
    ZappRow(
        title = row.name,
        subtitle = row.requirement?.getValue(),
        titleColor = if (row.isVerified) c.textMuted else c.text,
        trailing = {
            if (row.isVerified) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ROW_TRAILING_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = c.success,
                        modifier = Modifier.size(CHECK_SIZE),
                    )
                    // Says the state, not just the reward: a bare "50 RP" beside a tick reads as
                    // an offer rather than as points already banked.
                    BasicText(
                        text = stringResource(R.string.increase_reputation_verified_reward, row.reward.getValue()),
                        style = ZappTheme.typography.rowSubtitle.copy(color = c.success),
                    )
                }
            } else {
                // Two lines, right-aligned: what the account is worth in points, and what that is
                // worth in dollars of limit. The second is the one people actually decide on.
                Column(horizontalAlignment = Alignment.End) {
                    BasicText(
                        text = row.reward.getValue(),
                        style = ZappTheme.typography.rowSubtitle.copy(color = c.accentText),
                    )
                    row.limitGain?.let {
                        BasicText(
                            text = it.getValue(),
                            style = ZappTheme.typography.caption.copy(color = c.textMuted),
                        )
                    }
                }
            }
        },
        // Verified rows stay listed and inert: hiding one reads as a bug, and nothing else in the
        // app tells the user that account is already spent.
        onClick = row.onClick.takeIf { !row.isVerified },
    )
}

@Composable
private fun RunContent(run: VerificationRun, state: IncreaseReputationState) {
    Column(verticalArrangement = Arrangement.spacedBy(REPUTATION_SECTION_GAP)) {
        if (run.stage == VerificationStage.DONE) {
            ZappSuccessHeader(
                title = run.message,
                subtitle = run.newBuyLimit,
            )
            run.newPoints?.let {
                BasicText(
                    text = stringResource(R.string.reputation_rp_amount, it),
                    style = ZappTheme.typography.display.copy(color = ZappTheme.colors.text),
                )
            }
        } else {
            BasicText(
                text = run.message.getValue(),
                style = ZappTheme.typography.body.copy(color = ZappTheme.colors.text),
            )
            ZappStepList(steps = run.steps)
            if (run.stage == VerificationStage.VERIFYING) {
                ReputationNotice(stringResource(R.string.increase_reputation_waiting_help))
            }
        }
        run.error?.let {
            BasicText(
                text = it.getValue(),
                style = ZappTheme.typography.body.copy(color = ZappTheme.colors.danger),
            )
        }
        // Cancelling lives in the body, never in the dock: the dock carries the way forward.
        state.secondaryAction?.let { action ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ZappCompactButton(text = action.text.getValue(), onClick = action.onClick)
            }
        }
    }
}

@Composable
private fun BottomDock(state: IncreaseReputationState, uriHandler: UriHandler) {
    val run = state.run
    ZappBottomActionBar(
        onBack = state.onBack,
        primaryAction = {
            val action = state.primaryAction ?: return@ZappBottomActionBar
            val modifier = Modifier.weight(1f).padding(start = REPUTATION_BOTTOM_BAR_GAP)
            when {
                run?.stage == VerificationStage.DONE -> {
                    ZappDoneButton(text = action.text.getValue(), modifier = modifier, onClick = action.onClick)
                }

                run?.stage == VerificationStage.READY -> {
                    ZappButton(
                        text = action.text.getValue(),
                        modifier = modifier,
                        // Opening the Verifier is the one thing only the view can do, and the VM
                        // must not start polling until it has actually happened.
                        onClick = {
                            openVerifier(uriHandler, run)
                            action.onClick()
                        },
                    )
                }

                else -> {
                    ZappButton(
                        text = action.text.getValue(),
                        enabled = action.isEnabled,
                        modifier = modifier,
                        onClick = action.onClick,
                    )
                }
            }
        },
    )
}

/**
 * Android resolves the share link to the Verifier when it is installed and to a browser otherwise.
 * A device with neither throws, so the store links follow — `market://` first, then the same page
 * over https, which is all a `foss` build on a de-Googled phone can open.
 *
 * The user is not resumed into this session afterwards; nothing carries it across an install.
 * They come back to this screen, which is still holding the live session, and tap again.
 */
private fun openVerifier(uriHandler: UriHandler, run: VerificationRun) {
    val candidates = listOfNotNull(run.launchUrl, run.installIntentUrl, run.storeUrl)
    candidates.firstOrNull { url -> runCatching { uriHandler.openUri(url) }.isSuccess }
}

private val CHECK_SIZE = 18.dp
private val ROW_TRAILING_GAP = 6.dp
