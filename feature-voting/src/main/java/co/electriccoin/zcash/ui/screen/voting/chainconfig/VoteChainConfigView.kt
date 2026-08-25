package co.electriccoin.zcash.ui.screen.voting.chainconfig

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.RadioButtonState
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.ZashiTextField
import co.electriccoin.zcash.ui.design.component.ZashiTextFieldDefaults
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.voting.VoteButton
import co.electriccoin.zcash.ui.screen.voting.VoteConfirmationBottomSheet
import co.electriccoin.zcash.ui.screen.voting.component.VoteAppBar
import co.electriccoin.zcash.ui.screen.voting.voteBarAction
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun VoteChainConfigView(state: VoteChainConfigState?) {
    if (state == null) {
        CircularScreenProgressIndicator()
        return
    }

    VoteConfirmationBottomSheet(state = state.errorSheet)
    state.editor?.let { editor ->
        ZashiScreenModalBottomSheet(
            onDismissRequest = editor.cancelButton.onClick,
            dragHandle = null
        ) { contentPadding ->
            EditorSheet(
                state = editor,
                contentPadding = contentPadding
            )
        }
    }
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = { VoteAppBar(title = stringResource(R.string.coinVote_configSettings_screenTitle)) },
        bottomBar = {
            if (state.editor == null) {
                BottomActions(state = state, modifier = Modifier.fillMaxWidth())
            }
        },
        content = { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.xl3)
            ) {
                item(key = "intro") { Intro() }
                item(key = "chains") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        state.chains.forEach { ChainItem(it) }
                    }
                }
                if (state.chains.size == 1) {
                    item(key = "empty_custom") {
                        BasicText(
                            text = stringResource(R.string.vote_chain_config_custom_empty),
                            style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun Intro() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.coinVote_configSettings_sectionTitle),
            style = ZappTheme.typography.sectionTitle,
            fontWeight = FontWeight.SemiBold,
            color = ZappTheme.colors.text
        )
        Text(
            text = stringResource(R.string.coinVote_configSettings_sectionSubtitle),
            style = ZappTheme.typography.rowSubtitle,
            color = ZappTheme.colors.textMuted
        )
    }
}

@Composable
private fun ChainItem(state: VoteChainConfigItemState) {
    val isSelected = state.radioButtonState.isChecked

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = ZappTheme.colors.border,
                            shape = RectangleShape,
                        )
                    } else {
                        Modifier
                    }
                ).clickable(
                    enabled = !state.radioButtonState.isChecked,
                    role = Role.RadioButton,
                    onClick = state.radioButtonState.onClick
                ),
        shape = RectangleShape,
        color = if (isSelected) ZappTheme.colors.surface else ZappTheme.colors.surfaceAlt,
        border =
            if (isSelected) {
                BorderStroke(1.dp, ZappTheme.colors.border)
            } else {
                null
            }
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 2.dp,
                                color = ZappTheme.colors.border,
                                shape = RectangleShape
                            )
                        } else {
                            Modifier
                        }
                    ).padding(
                        start = ZappTheme.spacing.xl2,
                        top = ZappTheme.spacing.xl,
                        end = ZappTheme.spacing.xl,
                        bottom = ZappTheme.spacing.xl
                    ),
            horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.xl),
            verticalAlignment = Alignment.Top
        ) {
            RadioIndicator(
                isSelected = isSelected,
                modifier = Modifier.padding(top = 2.dp)
            )

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(end = ZappTheme.spacing.xl)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.radioButtonState.text.getValue(),
                        style = ZappTheme.typography.rowTitle,
                        fontWeight = FontWeight.Medium,
                        color = ZappTheme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(weight = 1f, fill = false)
                    )
                    if (state.isDefault) {
                        DefaultBadge()
                    }
                }
                Text(
                    text = state.radioButtonState.subtitle?.getValue() ?: state.fullUrl.getValue(),
                    style = ZappTheme.typography.rowSubtitle,
                    color = ZappTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }

            state.editButton?.let { editButton ->
                IconButton(
                    onClick = editButton.onClick,
                    enabled = editButton.isEnabled,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_chevron_right),
                        contentDescription = editButton.text.getValue(),
                        tint = ZappTheme.colors.text
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioIndicator(
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier =
            modifier
                .size(20.dp)
                .background(
                    color = if (isSelected) ZappTheme.colors.accent else ZappTheme.colors.surface,
                    shape = RectangleShape
                ).border(
                    width = 1.dp,
                    color = if (isSelected) ZappTheme.colors.accent else ZappTheme.colors.borderStrong,
                    shape = RectangleShape
                ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(ZappTheme.colors.surface, RectangleShape)
            )
        }
    }
}

@Composable
private fun BottomActions(
    state: VoteChainConfigState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // "Add a source" is a list action, so it stays above the bar. Save is the screen's
        // primary action and belongs beside back, in the shared bar every other page uses.
        AddCustomSourceButton(
            state = state,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZappTheme.spacing.xl)
                    .padding(bottom = ZappTheme.spacing.md)
        )
        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction = { VoteButton(state.saveChangesButton, modifier = voteBarAction()) }
        )
    }
}

@Composable
private fun AddCustomSourceButton(
    state: VoteChainConfigState,
    modifier: Modifier = Modifier
) {
    ZappButton(
        text = stringResource(R.string.coinVote_configSettings_addCustomSource),
        modifier = modifier,
        variant = ZappButtonVariant.Secondary,
        enabled = !state.isValidating,
        loading = state.isValidating,
        leadingIcon = Icons.Default.Add,
        onClick = state.onAddCustom,
    )
}

@Composable
private fun DefaultBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RectangleShape,
        color = ZappTheme.colors.chipBg,
        border = BorderStroke(1.dp, ZappTheme.colors.border)
    ) {
        Text(
            text = stringResource(R.string.coinVote_configSettings_defaultBadge),
            style = ZappTheme.typography.caption,
            fontWeight = FontWeight.Medium,
            color = ZappTheme.colors.text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun EditorSheet(
    state: VoteChainConfigEditorState,
    contentPadding: PaddingValues
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    Column {
        SheetHeader(state)
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = contentPadding.calculateBottomPadding())
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ZappTheme.spacing.xl3)
            ) {
                Text(
                    text = state.title.getValue(),
                    style = ZappTheme.typography.sectionTitle,
                    fontWeight = FontWeight.SemiBold,
                    color = ZappTheme.colors.text
                )
                Spacer(modifier = Modifier.height(ZappTheme.spacing.md))
                Text(
                    text = state.description.getValue(),
                    style = ZappTheme.typography.rowSubtitle,
                    color = ZappTheme.colors.text
                )
                Spacer(modifier = Modifier.height(ZappTheme.spacing.xl3))
                FieldLabel(text = stringResource(R.string.coinVote_configSettings_titleField))
                Spacer(modifier = Modifier.height(ZappTheme.spacing.md))
                ZashiTextField(
                    state = state.name,
                    singleLine = true,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    innerModifier = ZashiTextFieldDefaults.innerModifier.height(46.dp),
                    colors = sheetTextFieldColors(isFocusedByDefault = true)
                )
                Spacer(modifier = Modifier.height(ZappTheme.spacing.xl))
                FieldLabel(text = stringResource(R.string.coinVote_configSettings_urlField))
                Spacer(modifier = Modifier.height(ZappTheme.spacing.md))
                ZashiTextField(
                    state = state.url,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.coinVote_configSettings_urlPlaceholder),
                            style = ZappTheme.typography.rowTitle,
                            color = ZappTheme.colors.textMuted
                        )
                    },
                    trailingIcon =
                        if (state.showsUrlCopyButton) {
                            {
                                IconButton(
                                    onClick = state.onUrlCopyClick,
                                    enabled = state.url.isEnabled,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_copy),
                                        contentDescription =
                                            stringResource(co.electriccoin.zcash.ui.design.R.string.general_copy),
                                        tint = ZappTheme.colors.textMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier =
                        Modifier
                            .fillMaxWidth(),
                    innerModifier = ZashiTextFieldDefaults.innerModifier.height(46.dp),
                    colors = sheetTextFieldColors(isFocusedByDefault = false)
                )
                Spacer(modifier = Modifier.height(ZappTheme.spacing.xl3))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(ZappTheme.spacing.lg)
                ) {
                    state.deleteButton?.let { deleteButton ->
                        VoteButton(
                            state = deleteButton,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    VoteButton(
                        state = state.saveButton,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(EDITOR_FOCUS_DELAY_MS)
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}

@Composable
private fun SheetHeader(state: VoteChainConfigEditorState) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ZappTheme.spacing.xl)
                .padding(bottom = ZappTheme.spacing.xl3),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = state.sheetTitle.getValue().uppercase(),
            style = ZappTheme.typography.rowTitle,
            fontWeight = FontWeight.SemiBold,
            color = ZappTheme.colors.text
        )
        Surface(
            shape = RectangleShape,
            color = ZappTheme.colors.surface,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .size(44.dp)
                    .clickable { state.cancelButton.onClick() }
        ) {
            Icon(
                painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_navigation_close),
                contentDescription = null,
                tint = ZappTheme.colors.textMuted,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = ZappTheme.typography.rowSubtitle,
        fontWeight = FontWeight.Medium,
        color = ZappTheme.colors.text
    )
}

@Composable
private fun sheetTextFieldColors(isFocusedByDefault: Boolean) =
    ZashiTextFieldDefaults.defaultColors(
        textColor = ZappTheme.colors.text,
        borderColor = if (isFocusedByDefault) ZappTheme.colors.border else Color.Unspecified,
        focusedBorderColor = ZappTheme.colors.border,
        containerColor = ZappTheme.colors.surface,
        focusedContainerColor = ZappTheme.colors.surface,
        placeholderColor = ZappTheme.colors.textMuted
    )

private const val EDITOR_FOCUS_DELAY_MS = 100L

@PreviewScreens
@Composable
private fun VoteChainConfigPreview() =
    ZcashTheme {
        VoteChainConfigView(
            state =
                VoteChainConfigState.preview.copy(
                    chains =
                        listOf(
                            VoteChainConfigItemState.preview,
                            VoteChainConfigItemState.preview.copy(
                                id = "custom",
                                radioButtonState =
                                    RadioButtonState(
                                        text = stringRes("Local test"),
                                        subtitle = stringRes("https://example.com/static-voting-config.json"),
                                        isChecked = false,
                                        onClick = {},
                                    ),
                                fullUrl = stringRes("https://example.com/static-voting-config.json"),
                                isDefault = false,
                                editButton = ButtonState(text = stringRes("Edit"), style = ButtonStyle.TERTIARY),
                                deleteButton =
                                    ButtonState(
                                        text = stringRes("Delete"),
                                        style = ButtonStyle.DESTRUCTIVE2
                                    ),
                            ),
                        ),
                )
        )
    }
