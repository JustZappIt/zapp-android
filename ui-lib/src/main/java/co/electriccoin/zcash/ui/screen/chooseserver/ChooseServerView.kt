@file:Suppress("TooManyFunctions")

package co.electriccoin.zcash.ui.screen.chooseserver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.R.drawable
import co.electriccoin.zcash.ui.design.component.AlertDialogState
import co.electriccoin.zcash.ui.design.component.AppAlertDialog
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.LottieProgress
import co.electriccoin.zcash.ui.design.component.RadioButtonCheckedContent
import co.electriccoin.zcash.ui.design.component.RadioButtonState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiBadge
import co.electriccoin.zcash.ui.design.component.ZashiHorizontalDivider
import co.electriccoin.zcash.ui.design.component.ZashiRadioButton
import co.electriccoin.zcash.ui.design.component.ZashiTextField
import co.electriccoin.zcash.ui.design.component.ZashiTextFieldDefaults
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.orDark
import co.electriccoin.zcash.ui.design.util.stringRes
import java.util.UUID

@Composable
internal fun ChooseServerView(state: ChooseServerState?) {
    if (state == null) {
        CircularScreenProgressIndicator()
        return
    }

    val c = ZappTheme.colors
    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        containerColor = c.bg,
        topBar = {
            ZappScreenHeader(
                title = stringResource(id = R.string.choose_server_title),
                modifier = Modifier.testTag(CHOOSE_SERVER_TOP_APP_BAR),
            )
        },
        bottomBar = {
            ZappBottomActionBar(
                onBack = state.onBack,
                primaryAction = {
                    ZappButton(
                        text = state.saveButton.text.getValue(),
                        onClick = state.saveButton.onClick,
                        enabled = state.saveButton.isEnabled,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                    )
                },
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding()),
            contentPadding =
                PaddingValues(
                    top = paddingValues.calculateTopPadding() + ZcashTheme.dimens.spacingDefault,
                    bottom = ZcashTheme.dimens.spacingDefault,
                )
        ) {
            if (state.fastest.servers.isEmpty() && state.fastest.isLoading) {
                item(
                    key = "fastest_loading",
                    contentType = "fastest_loading"
                ) {
                    ServerLoading()
                }
            } else if (state.fastest.servers.isNotEmpty()) {
                serverListItems(state.fastest)
            }

            serverListItems(state.other)
        }

        if (state.dialogState != null) {
            ErrorDialog(dialogState = state.dialogState)
        }
    }
}

@Composable
private fun ServerLoading() {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LottieProgress(
            size = 32.dp,
        )
        Spacer(modifier = Modifier.height(20.dp))
        BasicText(
            text = stringResource(id = R.string.choose_server_loading_title),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text),
        )
        Spacer(modifier = Modifier.height(8.dp))
        BasicText(
            text = stringResource(id = R.string.choose_server_loading_subtitle),
            style = ZappTheme.typography.body.copy(color = c.textMuted),
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ErrorDialog(dialogState: ServerDialogState) {
    // TODO [#1276]: Once we ensure that the reason contains a localized message, we can leverage it for the UI prompt
    // TODO [#1276]: Consider adding support for a specific exception in AppAlertDialog
    // TODO [#1276]: https://github.com/Electric-Coin-Company/zashi-android/issues/1276

    when (dialogState) {
        is ServerDialogState.Validation -> {
            val c = ZappTheme.colors
            AppAlertDialog(
                title = dialogState.state.title.getValue(),
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState())
                    ) {
                        BasicText(
                            text = dialogState.state.text.getValue(),
                            style = ZappTheme.typography.body.copy(color = c.text),
                        )

                        if (dialogState.reason != null) {
                            Spacer(modifier = Modifier.height(ZcashTheme.dimens.spacingDefault))

                            BasicText(
                                text = dialogState.reason.getValue(),
                                style =
                                    ZappTheme.typography.body.copy(
                                        color = c.textMuted,
                                        fontStyle = FontStyle.Italic,
                                    ),
                            )
                        }
                    }
                },
                confirmButtonText =
                    dialogState.state.confirmButtonState
                        ?.text
                        ?.getValue(),
                onConfirmButtonClick = dialogState.state.confirmButtonState?.onClick
            )
        }
    }
}

@Suppress("CyclomaticComplexMethod")
private fun LazyListScope.serverListItems(state: ServerListState) {
    item(
        key =
            when (state) {
                is ServerListState.Fastest -> "fastest_header"
                is ServerListState.Other -> "other_header"
            },
        contentType =
            when (state) {
                is ServerListState.Fastest -> "fastest_header"
                is ServerListState.Other -> "other_header"
            }
    ) {
        when (state) {
            is ServerListState.Fastest -> FastestServersHeader(state = state)
            is ServerListState.Other -> OtherServersHeader(state = state)
        }
    }

    itemsIndexed(
        items = state.servers,
        contentType = { _, item -> item.contentType },
        key = { _, item -> item.key }
    ) { index, item ->
        val c = ZappTheme.colors
        when (item) {
            is ServerState.Custom -> {
                CustomServerRadioButton(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp)
                            .then(
                                if (item.radioButtonState.isChecked) {
                                    Modifier.background(c.surfaceAlt, RectangleShape)
                                } else {
                                    Modifier
                                }
                            ),
                    state = item
                )
            }

            is ServerState.Default -> {
                ZashiRadioButton(
                    state = item.radioButtonState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .then(
                                if (item.radioButtonState.isChecked && item.badge == null) {
                                    Modifier.background(c.surfaceAlt, RectangleShape)
                                } else {
                                    Modifier
                                }
                            ),
                    checkedContent = {
                        if (item.badge == null) {
                            RadioButtonCheckedContent(item.radioButtonState)
                        } else {
                            Image(
                                painter =
                                    painterResource(
                                        id =
                                            if (isSystemInDarkTheme()) {
                                                drawable.ic_radio_button_checked_variant_dark
                                            } else {
                                                drawable.ic_radio_button_checked_variant
                                            }
                                    ),
                                contentDescription = item.radioButtonState.text.getValue(),
                            )
                        }
                    },
                    trailingContent = {
                        if (item.badge != null) {
                            ZashiBadge(text = item.badge)
                        }
                    }
                )
            }
        }

        if (index != state.servers.lastIndex) {
            Spacer(modifier = Modifier.height(4.dp))
            ZashiHorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
        } else if (index == state.servers.lastIndex && state is ServerListState.Fastest) {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FastestServersHeader(state: ServerListState.Fastest) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ZappGroupHeader(text = state.title.getValue())
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier =
                Modifier
                    .clickable(onClick = state.retryButton.onClick)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = state.retryButton.text.getValue(),
                style = ZappTheme.typography.button.copy(color = c.text),
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (state.isLoading) {
                LottieProgress()
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_retry),
                    contentDescription = state.retryButton.text.getValue(),
                    colorFilter = ColorFilter.tint(c.text)
                )
            }
        }
    }
}

@Composable
private fun OtherServersHeader(state: ServerListState.Other) {
    Column(
        modifier = Modifier.padding(horizontal = 18.dp)
    ) {
        ZappGroupHeader(text = state.title.getValue())
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun CustomServerRadioButton(
    state: ServerState.Custom,
    modifier: Modifier = Modifier
) {
    val c = ZappTheme.colors
    Column(modifier = modifier) {
        ZashiRadioButton(
            state = state.radioButtonState,
            modifier = Modifier.fillMaxWidth(),
            isRippleEnabled = false,
            checkedContent = {
                if (state.badge == null) {
                    RadioButtonCheckedContent(state.radioButtonState)
                } else {
                    Image(
                        painter =
                            painterResource(
                                id =
                                    if (isSystemInDarkTheme()) {
                                        drawable.ic_radio_button_checked_variant_dark
                                    } else {
                                        drawable.ic_radio_button_checked_variant
                                    }
                            ),
                        contentDescription = state.radioButtonState.text.getValue(),
                    )
                }
            },
            trailingContent = {
                if (state.badge != null) {
                    ZashiBadge(
                        text = state.badge,
                    )
                    Spacer(
                        modifier = Modifier.width(8.dp),
                    )
                }
                val iconAngle =
                    animateFloatAsState(
                        targetValue = if (state.isExpanded) 180f else 0f,
                        label = "iconAngle"
                    )
                Image(
                    modifier =
                        Modifier
                            .align(Alignment.CenterVertically)
                            .rotate(iconAngle.value),
                    painter = painterResource(id = R.drawable.ic_expand),
                    contentDescription = state.radioButtonState.text.getValue(),
                    colorFilter = ColorFilter.tint(c.text)
                )
            }
        )

        AnimatedVisibility(visible = state.isExpanded) {
            val focusManager = LocalFocusManager.current
            Spacer(modifier = Modifier.height(ZcashTheme.dimens.spacingSmall))
            ZashiTextField(
                state = state.newServerTextFieldState,
                placeholder = {
                    BasicText(
                        text = stringResource(R.string.choose_server_textfield_hint),
                        style = ZappTheme.typography.body.copy(color = c.textSubtle),
                    )
                },
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(true) }),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 52.dp,
                            end = 20.dp,
                            bottom = 16.dp
                        ),
                colors =
                    ZashiTextFieldDefaults.defaultColors(
                        containerColor = c.bg,
                        textColor = c.text,
                        borderColor = c.border,
                    ) orDark
                        ZashiTextFieldDefaults.defaultColors(
                            containerColor = c.surfaceAlt,
                            hintColor = c.textSubtle,
                            textColor = c.text,
                            borderColor = c.borderStrong,
                        )
            )
        }
    }
}

@Suppress("MagicNumber")
@Composable
private fun ChooseServerPreview(
    showFastestServerLoading: Boolean = true,
    dialogState: ServerDialogState? = null
) {
    var selectionIndex by remember { mutableIntStateOf(5) }
    val fastestServers =
        ServerListState.Fastest(
            title = stringRes("Fastest Servers"),
            servers =
                if (showFastestServerLoading) {
                    (1..3).map {
                        ServerState.Default(
                            key = UUID.randomUUID().toString(),
                            RadioButtonState(
                                text = stringRes("Some Server"),
                                isChecked = selectionIndex == it,
                                onClick = {
                                    selectionIndex = it
                                },
                                subtitle = null,
                            ),
                            badge = null,
                        )
                    }
                } else {
                    listOf()
                },
            retryButton =
                ButtonState(
                    text = stringRes("Save Button"),
                    onClick = {},
                ),
            isLoading = true,
        )
    ChooseServerView(
        state =
            ChooseServerState(
                fastest = fastestServers,
                other =
                    ServerListState.Other(
                        title = stringRes("Other Servers"),
                        servers =
                            (4..<12).map {
                                if (it == 5) {
                                    ServerState.Custom(
                                        key = UUID.randomUUID().toString(),
                                        RadioButtonState(
                                            text = stringRes("Custom Server"),
                                            isChecked = selectionIndex == it,
                                            onClick = {
                                                selectionIndex = it
                                            }
                                        ),
                                        newServerTextFieldState =
                                            TextFieldState(
                                                value = stringRes(""),
                                                error = null,
                                                onValueChange = { },
                                            ),
                                        badge = null,
                                        isExpanded = true
                                    )
                                } else {
                                    ServerState.Default(
                                        key = UUID.randomUUID().toString(),
                                        RadioButtonState(
                                            text = stringRes("Some Server"),
                                            isChecked = selectionIndex == it,
                                            onClick = {
                                                selectionIndex = it
                                            },
                                            subtitle = if (it == 6) stringRes("Default") else null,
                                        ),
                                        badge = if (it == 6) stringRes("Active") else null,
                                    )
                                }
                            }
                    ),
                saveButton =
                    ButtonState(
                        text = stringRes("Save Button"),
                        onClick = {},
                    ),
                dialogState = dialogState,
                onBack = {}
            ),
    )
}

@Suppress("UnusedPrivateMember")
@PreviewScreens
@Composable
private fun ChooseServerPreviewValidationDialog() =
    ZcashTheme {
        ChooseServerPreview(
            dialogState =
                ServerDialogState.Validation(
                    state =
                        AlertDialogState(
                            title = stringRes("title"),
                            text = stringRes("text"),
                        ),
                    reason = stringRes("reason")
                )
        )
    }

@Suppress("UnusedPrivateMember")
@PreviewScreens
@Composable
private fun ChooseServerPreviewLoading() =
    ZcashTheme {
        ChooseServerPreview(showFastestServerLoading = false)
    }

@Suppress("UnusedPrivateMember")
@PreviewScreens
@Composable
private fun ChooseServerPreviewData() =
    ZcashTheme {
        ChooseServerPreview()
    }

private const val CHOOSE_SERVER_TOP_APP_BAR = "choose_server_top_app_bar"
