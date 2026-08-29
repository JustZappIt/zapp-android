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
                            .size(INFO_TAP_TARGET.dp)
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
                    .padding(horizontal = HORIZONTAL_PADDING.dp, vertical = VERTICAL_PADDING.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP.dp)) {
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
                    horizontalArrangement = Arrangement.spacedBy(ROW_TRAILING_GAP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = c.success,
                        modifier = Modifier.size(CHECK_SIZE.dp),
                    )
                    BasicText(
                        text = row.reward.getValue(),
                        style = ZappTheme.typography.rowSubtitle.copy(color = c.success),
                    )
                }
            } else {
                BasicText(
                    text = row.reward.getValue(),
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.accentText),
                )
            }
        },
        // Verified rows stay listed and inert: hiding one reads as a bug, and nothing else in the
        // app tells the user that account is already spent.
        onClick = row.onClick.takeIf { !row.isVerified },
    )
}

@Composable
private fun RunContent(run: VerificationRun, state: IncreaseReputationState) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP.dp)) {
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
                Notice(stringResource(R.string.increase_reputation_waiting_help))
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
            val modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp)
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
 * A device with nothing registered for it throws, and the market intent takes the user to the
 * store and back to this same session after installing.
 */
private fun openVerifier(uriHandler: UriHandler, run: VerificationRun) {
    val url = run.launchUrl ?: return
    runCatching { uriHandler.openUri(url) }
        .onFailure { run.installIntentUrl?.let { intent -> runCatching { uriHandler.openUri(intent) } } }
}

@Composable
private fun Notice(text: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(ZappTheme.colors.surfaceAlt)
                .padding(NOTICE_PADDING.dp),
    ) {
        BasicText(text, style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted))
    }
}

private const val HORIZONTAL_PADDING = 18
private const val VERTICAL_PADDING = 16
private const val BOTTOM_BAR_GAP = 12
private const val SECTION_GAP = 16
private const val NOTICE_PADDING = 12
private const val CHECK_SIZE = 18
private const val ROW_TRAILING_GAP = 6
private const val INFO_TAP_TARGET = 48
