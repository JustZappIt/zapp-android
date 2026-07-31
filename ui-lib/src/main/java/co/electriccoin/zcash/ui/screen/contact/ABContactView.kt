package co.electriccoin.zcash.ui.screen.contact

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.PickerState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiAddressTextField
import co.electriccoin.zcash.ui.design.component.ZashiPicker
import co.electriccoin.zcash.ui.design.component.ZashiTextField
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
fun ABContactView(
    state: ABContactState,
    onSideEffect: (nameFocusRequester: FocusRequester, addressFocusRequester: FocusRequester) -> Unit = { _, _ -> }
) {
    val addressFocusRequester = remember { FocusRequester() }
    val nameFocusRequester = remember { FocusRequester() }
    BlankBgScaffold(
        topBar = {
            ContactTopAppBar(state = state)
        },
        bottomBar = {
            ZappBottomActionBar(onBack = state.onBack)
        }
    ) { paddingValues ->
        ContactViewInternal(
            state = state,
            modifier =
                Modifier
                    .fillMaxSize()
                    .scaffoldPadding(paddingValues)
                    .verticalScroll(rememberScrollState()),
            addressFocusRequester = addressFocusRequester,
            nameFocusRequester = nameFocusRequester
        )

        SideEffect {
            onSideEffect(nameFocusRequester, addressFocusRequester)
        }
    }
}

@Composable
private fun ContactViewInternal(
    state: ABContactState,
    addressFocusRequester: FocusRequester,
    nameFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Column(
        modifier = modifier,
    ) {
        BasicText(
            text = stringResource(id = R.string.contact_address_label),
            style =
                ZappTheme.typography.groupLabel.copy(
                    color = c.textMuted,
                    fontWeight = FontWeight.Black,
                ),
        )
        Spacer(6.dp)
        ZashiAddressTextField(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(addressFocusRequester),
            state = state.walletAddress,
            placeholder = {
                Text(
                    text = stringResource(id = R.string.contact_address_hint),
                    style = ZappTheme.typography.body.copy(color = c.textSubtle),
                )
            },
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Next
                )
        )
        Spacer(20.dp)
        BasicText(
            text = stringResource(id = R.string.contact_name_label),
            style =
                ZappTheme.typography.groupLabel.copy(
                    color = c.textMuted,
                    fontWeight = FontWeight.Black,
                ),
        )
        Spacer(6.dp)
        ZashiTextField(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocusRequester),
            state = state.contactName,
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Words
                ),
            placeholder = {
                Text(
                    text = stringResource(id = R.string.contact_name_hint),
                    style = ZappTheme.typography.body.copy(color = c.textSubtle),
                )
            }
        )

        if (state.chain != null) {
            Spacer(20.dp)
            BasicText(
                text = stringResource(R.string.contact_select_chain),
                style =
                    ZappTheme.typography.groupLabel.copy(
                        color = c.textMuted,
                        fontWeight = FontWeight.Black,
                    ),
            )
            Spacer(6.dp)
            ZashiPicker(state = state.chain)
        }
        Spacer(1f)
        Spacer(24.dp)
        ZappButton(
            text = state.positiveButton.text.getValue(),
            modifier = Modifier.fillMaxWidth(),
            variant = ZappButtonVariant.Primary,
            enabled = state.positiveButton.isEnabled,
            onClick = state.positiveButton.onClick,
        )

        state.negativeButton?.let {
            ZappButton(
                text = it.text.getValue(),
                modifier = Modifier.fillMaxWidth(),
                variant = ZappButtonVariant.Danger,
                enabled = it.isEnabled,
                onClick = it.onClick,
            )
        }
    }
}

@Composable
private fun ContactTopAppBar(
    state: ABContactState
) {
    ZappScreenHeader(
        title = state.title.getValue(),
        modifier =
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .testTag(ABContactTag.TOP_APP_BAR),
    )
}

@PreviewScreens
@Composable
private fun DataPreview() {
    ZcashTheme {
        ABContactView(
            state =
                ABContactState(
                    info = IconButtonState(R.drawable.ic_help) {},
                    title = stringRes("Title"),
                    walletAddress = TextFieldState(stringRes("Address")) {},
                    contactName = TextFieldState(stringRes("Name")) {},
                    chain =
                        PickerState(
                            bigIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_item_keystone),
                            smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_item_keystone),
                            text = stringRes("Text"),
                            placeholder = stringRes("Placeholder"),
                            onClick = {}
                        ),
                    negativeButton =
                        ButtonState(
                            text = stringRes("Negative"),
                        ),
                    positiveButton =
                        ButtonState(
                            text = stringRes("Positive"),
                        ),
                    onBack = {}
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun LoadingPreview() {
    ZcashTheme {
        ABContactView(
            state =
                ABContactState(
                    info = null,
                    title = stringRes("Title"),
                    walletAddress = TextFieldState(stringRes("Address")) {},
                    contactName = TextFieldState(stringRes("Name")) {},
                    chain = null,
                    negativeButton =
                        ButtonState(
                            text = stringRes("Add New Contact"),
                        ),
                    positiveButton =
                        ButtonState(
                            text = stringRes("Add New Contact"),
                        ),
                    onBack = {}
                ),
        )
    }
}
