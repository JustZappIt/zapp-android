// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.grouplink

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiModalBottomSheet
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappCompactButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappCopyIconButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSelectionRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappStatusChip
import co.electriccoin.zcash.ui.design.component.zapp.ZappToggle
import co.electriccoin.zcash.ui.design.component.zapp.ZappValueCard
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun GroupLinkView(
    state: GroupLinkState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        ZappScreenHeader(title = stringResource(R.string.group_link_header))

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    color = c.accent,
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(28.dp),
                )
            }

            state.card?.let { LinkCard(it) }
            state.warning?.let { Note(text = it.getValue(), style = t.body, color = c.text) }
            state.historyNote?.let { Note(text = it.getValue(), style = t.caption, color = c.textMuted) }
            state.notice?.let { Note(text = it.getValue(), style = t.caption, color = c.textMuted) }
            state.error?.let { Note(text = it.getValue(), style = t.caption, color = c.danger) }

            state.actions.forEach { action ->
                ZappButton(
                    text = action.text.getValue(),
                    variant = action.variant,
                    enabled = action.isEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = action.onClick,
                )
            }

            if (state.requests.isNotEmpty()) {
                Requests(requests = state.requests)
            }

            if (state.rows.isNotEmpty() || state.toggles.isNotEmpty()) {
                Settings(state = state)
            }
            Spacer(Modifier.height(8.dp))
        }

        ZappBottomActionBar(onBack = state.onBack)
    }

    state.picker?.let { PickerSheet(it) }
    ZappConfirmationBottomSheet(state.confirmation)
}

@Composable
private fun LinkCard(card: GroupLinkCardState) {
    val copyLabel = stringResource(R.string.group_link_copy)
    ZappValueCard(
        value = card.link,
        leading = { LinkQrCode(card) },
        trailing = {
            ZappCopyIconButton(
                isCopied = card.isCopied,
                contentDescription = copyLabel,
                onClick = card.onCopyClick,
            )
        },
    )
}

@Composable
private fun LinkQrCode(card: GroupLinkCardState) {
    ZashiQr(
        state =
            QrState(
                qrData = card.link,
                contentDescription = stringRes(R.string.group_link_qr_content_description),
            ),
        modifier = Modifier.semantics { role = Role.Button },
        qrSize = 72.dp,
        contentPadding = PaddingValues(0.dp),
        fullscreenAction = {
            ZappButton(
                text = stringResource(R.string.group_link_copy),
                leadingIcon = Icons.Default.ContentCopy,
                onClick = card.onCopyClick,
            )
        },
    )
}

@Composable
private fun Requests(requests: List<GroupLinkRequestState>) {
    val c = ZappTheme.colors
    ZappGroupHeader(text = stringResource(R.string.group_link_requests_label))
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape),
    ) {
        requests.forEachIndexed { index, request ->
            RequestRow(request = request)
            if (index != requests.lastIndex) ZappRowDivider(inset = true)
        }
    }
}

@Composable
private fun RequestRow(request: GroupLinkRequestState) {
    val c = ZappTheme.colors
    val t = ZappTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(text = request.name, style = t.rowTitle.copy(color = c.text))
        BasicText(text = request.subtitle.getValue(), style = t.rowSubtitle.copy(color = c.textMuted))
        request.contactHint?.let {
            BasicText(text = it.getValue(), style = t.caption.copy(color = c.textMuted))
        }
        request.tag?.let { ZappStatusChip(text = it.getValue()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZappCompactButton(
                text = stringResource(R.string.group_link_request_approve),
                enabled = request.isEnabled,
                onClick = request.onApprove,
            )
            ZappCompactButton(
                text = stringResource(R.string.group_link_request_decline),
                enabled = request.isEnabled,
                onClick = request.onDecline,
            )
        }
    }
}

@Composable
private fun Settings(state: GroupLinkState) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape),
    ) {
        state.rows.forEachIndexed { index, row ->
            ZappRow(
                title = row.title.getValue(),
                trailing = {
                    BasicText(
                        text = row.value.getValue(),
                        style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                    )
                },
                onClick = row.onClick,
            )
            if (index != state.rows.lastIndex || state.toggles.isNotEmpty()) ZappRowDivider(inset = true)
        }
        state.toggles.forEachIndexed { index, toggle ->
            ZappRow(
                title = toggle.title.getValue(),
                subtitle = toggle.subtitle?.getValue(),
                trailing = { ZappToggle(checked = toggle.isChecked, onClick = toggle.onClick) },
                onClick = toggle.onClick,
            )
            if (index != state.toggles.lastIndex) ZappRowDivider(inset = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(picker: GroupLinkPickerState) {
    val c = ZappTheme.colors
    ZashiModalBottomSheet(
        onDismissRequest = picker.onDismiss,
        containerColor = c.surface,
        scrimColor = c.overlay,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 24.dp),
        ) {
            BasicText(
                text = picker.title.getValue(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            picker.options.forEach { option ->
                ZappSelectionRow(
                    title = option.title.getValue(),
                    subtitle = null,
                    isSelected = option.isSelected,
                    onClick = option.onClick,
                )
            }
        }
    }
}

@Composable
private fun Note(
    text: String,
    style: TextStyle,
    color: Color,
) = BasicText(text = text, style = style.copy(color = color), modifier = Modifier.fillMaxWidth())

@PreviewScreens
@Composable
private fun GroupLinkPreview() =
    ZcashTheme {
        GroupLinkView(
            state =
                GroupLinkState(
                    isLoading = false,
                    card =
                        GroupLinkCardState(
                            link = "https://join.justzappit.xyz/g/v1/AQEAAAAAAAAAAAAAAAAAAAAAAAAA",
                            isCopied = false,
                            onCopyClick = {},
                        ),
                    warning = stringRes(R.string.group_link_warning),
                    historyNote = stringRes(R.string.group_link_history_note),
                    notice = null,
                    error = null,
                    actions =
                        listOf(
                            GroupLinkActionState(stringRes(R.string.group_link_copy), ZappButtonVariant.Primary) {},
                            GroupLinkActionState(stringRes(R.string.group_link_share), ZappButtonVariant.Secondary) {},
                        ),
                    requests =
                        listOf(
                            GroupLinkRequestState(
                                key = "aa",
                                name = "Sam",
                                subtitle = stringRes(R.string.group_link_request_subtitle),
                                contactHint = null,
                                tag = null,
                                isEnabled = true,
                                onApprove = {},
                                onDecline = {},
                            ),
                        ),
                    rows =
                        listOf(
                            GroupLinkRowState(
                                title = stringRes(R.string.group_link_expiry_label),
                                value = stringRes(R.string.group_link_expiry_never),
                                onClick = {},
                            ),
                        ),
                    toggles =
                        listOf(
                            GroupLinkToggleState(
                                title = stringRes(R.string.group_link_approval_toggle),
                                subtitle = stringRes(R.string.group_link_approval_help),
                                isChecked = false,
                                onClick = {},
                            ),
                        ),
                    picker = null,
                    confirmation = null,
                    onBack = {},
                ),
        )
    }
