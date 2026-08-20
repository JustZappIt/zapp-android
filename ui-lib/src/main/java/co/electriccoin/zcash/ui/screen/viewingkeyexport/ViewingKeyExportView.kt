// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.viewingkeyexport

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.common.security.PinVerifyOverlay
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyExportAccount
import co.electriccoin.zcash.ui.common.usecase.ViewingKeyType
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappCopyIconButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun ViewingKeyExportView(
    state: ViewingKeyExportState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    val infoContentDescription = stringResource(R.string.viewing_key_export_info_content_description)
    var showInfo by remember { mutableStateOf(false) }

    if (shouldSecureScreen) {
        SecureScreen()
    }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = {
            ZappScreenHeader(
                title = stringResource(R.string.viewing_key_export_title),
                subtitle = stringResource(R.string.viewing_key_export_subtitle),
                right = {
                    Box(
                        modifier =
                            Modifier
                                .size(spacing.xl6)
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
        },
        bottomBar = {
            ZappBottomActionBar(
                onBack = state.onBack,
                isBackEnabled = !state.isAuthenticating,
                primaryAction =
                    if (state.revealedKey == null) {
                        {
                            ZappButton(
                                text =
                                    if (state.isAuthenticating) {
                                        stringResource(R.string.viewing_key_export_authenticating)
                                    } else {
                                        stringResource(R.string.viewing_key_export_reveal)
                                    },
                                leadingIcon = Icons.Default.Visibility,
                                enabled = state.canReveal,
                                onClick = state.onReveal,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .padding(start = spacing.lg),
                            )
                        }
                    } else {
                        null
                    },
            )
        },
    ) { contentPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
        ) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = c.accent)
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                ) {
                    if (state.accounts.size > 1) {
                        AccountPicker(state)
                    }

                    AccessLevelPicker(state)

                    if (state.revealedKey == null) {
                        Acknowledgement(state)
                    } else {
                        RevealedKeySection(state)
                    }

                    state.error?.let { ErrorMessage(it) }

                    Spacer(Modifier.height(spacing.xl))
                }
            }
        }
    }

    state.pinVerify?.let { PinVerifyOverlay(state = it) }

    if (showInfo) ViewingKeyExportInfoSheet { showInfo = false }
}

@Composable
private fun AccountPicker(state: ViewingKeyExportState) {
    val spacing = ZappTheme.spacing
    ZappGroupHeader(text = stringResource(R.string.viewing_key_export_account_label))
    Column(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        state.accounts.forEach { account ->
            SelectionCard(
                title = account.label.getValue(),
                subtitle = stringResource(R.string.viewing_key_export_account_index, account.accountIndex),
                selected = account.accountId == state.selectedAccountId,
                enabled = state.revealedKey == null && !state.isAuthenticating,
                onClick = { state.onAccountSelected(account.accountId) },
            )
        }
    }
}

@Composable
private fun AccessLevelPicker(state: ViewingKeyExportState) {
    val spacing = ZappTheme.spacing
    ZappGroupHeader(text = stringResource(R.string.viewing_key_export_access_level_label))
    Column(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        ViewingKeyType.entries.forEach { keyType ->
            val available = keyType in state.selectedAccount?.availableKeyTypes.orEmpty()
            SelectionCard(
                title =
                    stringResource(
                        when (keyType) {
                            ViewingKeyType.UFVK -> R.string.viewing_key_export_ufvk_title
                            ViewingKeyType.UIVK -> R.string.viewing_key_export_uivk_title
                        }
                    ),
                subtitle =
                    stringResource(
                        when (keyType) {
                            ViewingKeyType.UFVK -> R.string.viewing_key_export_ufvk_description
                            ViewingKeyType.UIVK -> R.string.viewing_key_export_uivk_description
                        }
                    ),
                supporting = stringResource(R.string.viewing_key_export_unavailable).takeIf { !available },
                selected = state.selectedKeyType == keyType,
                enabled = available && state.revealedKey == null && !state.isAuthenticating,
                onClick = { state.onKeyTypeSelected(keyType) },
            )
        }
    }
}

@Composable
private fun SelectionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    supporting: String? = null,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) c.accentSoft else c.surface, RectangleShape)
                .border(
                    BorderStroke(1.dp, if (selected) c.accent else c.border),
                    RectangleShape,
                ).selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            BasicText(
                text = title,
                style = ZappTheme.typography.rowTitle.copy(color = if (enabled) c.text else c.textSubtle),
            )
            BasicText(
                text = subtitle,
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
            )
            supporting?.let {
                BasicText(
                    text = it,
                    style = ZappTheme.typography.caption.copy(color = c.textSubtle),
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = c.accentText,
                modifier = Modifier.size(spacing.xl2),
            )
        }
    }
}

@Composable
private fun Acknowledgement(state: ViewingKeyExportState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.isAcknowledged,
                    enabled = state.isSelectedKeyAvailable && !state.isAuthenticating,
                    role = Role.Checkbox,
                    onValueChange = state.onAcknowledgementChanged,
                ).padding(horizontal = spacing.xl, vertical = spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .size(spacing.xl3)
                    .background(if (state.isAcknowledged) c.accentSoft else c.surface, RectangleShape)
                    .border(1.dp, if (state.isAcknowledged) c.accent else c.border, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isAcknowledged) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = c.accentText,
                    modifier = Modifier.size(spacing.xl2),
                )
            }
        }
        BasicText(
            text = stringResource(R.string.viewing_key_export_acknowledgement),
            style = ZappTheme.typography.body.copy(color = c.text),
        )
    }
}

@Composable
private fun RevealedKeySection(state: ViewingKeyExportState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    val key = state.revealedKey ?: return
    val sharePickerText = stringResource(R.string.viewing_key_export_share_picker)

    ZappGroupHeader(text = stringResource(R.string.viewing_key_export_revealed_label))
    ZappBorderedCard(
        modifier = Modifier.padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text =
                    stringResource(
                        when (key.keyType) {
                            ViewingKeyType.UFVK -> R.string.viewing_key_export_ufvk_title
                            ViewingKeyType.UIVK -> R.string.viewing_key_export_uivk_title
                        }
                    ),
                style = ZappTheme.typography.eyebrow.copy(color = c.accentText),
            )
            ZappCopyIconButton(
                isCopied = state.isCopied,
                contentDescription = stringResource(R.string.viewing_key_export_copy),
                onClick = state.onCopy,
            )
        }
        BasicText(
            text = key.encodedKey,
            style = ZappTheme.typography.mono.copy(color = c.text),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(ViewingKeyExportTag.REVEALED_KEY),
        )
        ZappButton(
            text = stringResource(R.string.viewing_key_export_share),
            leadingIcon = Icons.Default.Share,
            onClick = { state.onShare(sharePickerText) },
            modifier = Modifier.fillMaxWidth(),
        )
        ZappButton(
            text = stringResource(R.string.viewing_key_export_hide),
            leadingIcon = Icons.Default.VisibilityOff,
            variant = ZappButtonVariant.Ghost,
            onClick = state.onHide,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ErrorMessage(error: ViewingKeyExportError) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    val message =
        when (error) {
            ViewingKeyExportError.LOAD_FAILED -> R.string.viewing_key_export_load_failed
            ViewingKeyExportError.AUTHENTICATION_FAILED -> R.string.viewing_key_export_auth_failed
            ViewingKeyExportError.KEY_UNAVAILABLE -> R.string.viewing_key_export_key_unavailable
            ViewingKeyExportError.SHARE_FAILED -> R.string.viewing_key_export_share_failed
        }
    BasicText(
        text = stringResource(message),
        style = ZappTheme.typography.body.copy(color = c.danger),
        modifier = Modifier.padding(horizontal = spacing.xl, vertical = spacing.md),
    )
}

internal object ViewingKeyExportTag {
    const val REVEALED_KEY = "viewing_key_export_revealed_key"
}

@PreviewScreens
@Composable
private fun ViewingKeyExportPreview() =
    ProvideZappTheme {
        ViewingKeyExportView(
            state =
                ViewingKeyExportState(
                    accounts = emptyList(),
                    selectedAccountId = null,
                    selectedKeyType = ViewingKeyType.UFVK,
                    isAcknowledged = false,
                    isLoading = false,
                    isAuthenticating = false,
                    isCopied = false,
                    revealedKey = null,
                    error = null,
                    pinVerify = null,
                    onAccountSelected = {},
                    onKeyTypeSelected = {},
                    onAcknowledgementChanged = {},
                    onReveal = {},
                    onCopy = {},
                    onShare = {},
                    onHide = {},
                    onBack = {},
                )
        )
    }
