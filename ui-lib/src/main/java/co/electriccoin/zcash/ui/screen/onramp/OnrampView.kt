package co.electriccoin.zcash.ui.screen.onramp

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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappDoneButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import kotlinx.coroutines.delay

// Deliberately not SecureScreen: FLAG_SECURE blanks screenshots, and on a single phone that is the
// only way to get the QR into a payment app — you cannot scan your own screen, so the user
// screenshots it and scans from their gallery. Nothing here is secret the way a seed phrase is:
// the merchant's handle and the amount are what the user is about to hand a payment app anyway.
@Composable
internal fun OnrampView(state: OnrampState) {
    val c = ZappTheme.colors
    val infoContentDescription = stringResource(R.string.onramp_info_content_description)
    var showInfo by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    // Reveal the success moment: on completion, glide back to the top so the badge + headline land.
    LaunchedEffect(state.mode) {
        if (state.mode == OnrampMode.COMPLETION) {
            delay(COMPLETION_SCROLL_DELAY_MS)
            scrollState.animateScrollTo(0)
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .imePadding(),
    ) {
        ZappScreenHeader(
            title = stringResource(R.string.onramp_title),
            right = {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
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
            when (state.mode) {
                OnrampMode.LOADING -> LoadingContent()
                OnrampMode.UNAVAILABLE -> UnavailableContent(state)
                OnrampMode.AMOUNT -> AmountContent(state)
                OnrampMode.CONFIRMATION -> ConfirmationContent(state)
                OnrampMode.PROGRESS -> ProgressContent(state)
                OnrampMode.PAYMENT -> PaymentContent(state)
                OnrampMode.COMPLETION -> CompletionContent(state)
            }
        }
        BottomDock(state)
    }
    if (showInfo) OnrampInfoSheet(state) { showInfo = false }
    if (state.isPaidConfirmVisible) PaidConfirmSheet(state)
    if (state.isSendBaseBalanceConfirmVisible) BaseRefundConfirmSheet(state)
}

/**
 * Confirming payment releases the merchant's USDC on-chain, so it sits behind a deliberate second
 * step rather than a single dock tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaidConfirmSheet(state: OnrampState) {
    ZashiScreenModalBottomSheet(onDismissRequest = state.onDismissPaidConfirm) { padding ->
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                stringResource(R.string.onramp_paid_confirm_title),
                style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
            )
            BasicText(
                stringResource(R.string.onramp_paid_confirm_body),
                style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
            )
            ZappButton(
                text = stringResource(R.string.onramp_paid_confirm_action),
                onClick = state.onConfirmPaid,
                modifier = Modifier.fillMaxWidth(),
            )
            ZappButton(
                text = stringResource(R.string.onramp_paid_confirm_cancel),
                onClick = state.onDismissPaidConfirm,
                variant = ZappButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaseRefundConfirmSheet(state: OnrampState) {
    ZashiScreenModalBottomSheet(onDismissRequest = state.onDismissSendBaseBalanceToZec) { padding ->
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                stringResource(R.string.onramp_send_to_zec_confirm_title),
                style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
            )
            BasicText(
                stringResource(
                    R.string.onramp_send_to_zec_confirm_body,
                    state.baseBalance ?: stringResource(R.string.onramp_base_balance_unavailable),
                ),
                style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
            )
            ZappButton(
                text = stringResource(R.string.onramp_send_to_zec_confirm_action),
                onClick = state.onConfirmSendBaseBalanceToZec,
                modifier = Modifier.fillMaxWidth(),
            )
            ZappButton(
                text = stringResource(R.string.onramp_send_to_zec_cancel),
                onClick = state.onDismissSendBaseBalanceToZec,
                variant = ZappButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = ZappTheme.colors.accent)
    }
}

@Composable
private fun UnavailableContent(state: OnrampState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BasicText(
            text = stringResource(R.string.onramp_unavailable_title),
            style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
        )
        Notice(stringResource(R.string.onramp_unavailable_body))
        ErrorText(state)
    }
}

@Composable
private fun BottomDock(state: OnrampState) {
    val action = state.primaryAction
    ZappBottomActionBar(
        onBack = state.onBack,
        isBackEnabled = !state.isSendingBaseBalanceToZec,
        primaryAction = {
            if (state.mode == OnrampMode.COMPLETION) {
                ZappDoneButton(
                    text = action.text.getValue(),
                    modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                    onClick = action.onClick,
                )
            } else {
                ZappButton(
                    text = action.text.getValue(),
                    enabled = action.isEnabled,
                    modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                    onClick = action.onClick,
                )
            }
        },
    )
}

@Composable
internal fun Notice(text: String) {
    Row(modifier = Modifier.fillMaxWidth().background(ZappTheme.colors.surfaceAlt).padding(12.dp)) {
        BasicText(text, style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted))
    }
}

@Composable
internal fun ErrorText(state: OnrampState) {
    state.error?.let {
        BasicText(it.getValue(), style = ZappTheme.typography.body.copy(color = ZappTheme.colors.danger))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnrampInfoSheet(state: OnrampState, onDismiss: () -> Unit) {
    ZashiScreenModalBottomSheet(onDismissRequest = onDismiss) { padding ->
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                stringResource(R.string.onramp_info_title),
                style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
            )
            BasicText(
                stringResource(R.string.onramp_info_body),
                style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
            )
            BasicText(
                stringResource(R.string.onramp_info_custody),
                style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
            )
            OnrampDestinationInfo(state)
            ZappButton(
                text = stringResource(co.electriccoin.zcash.ui.design.R.string.general_ok),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val HORIZONTAL_PADDING = 18
private const val VERTICAL_PADDING = 16
private const val BOTTOM_BAR_GAP = 12
private const val COMPLETION_SCROLL_DELAY_MS = 80L
