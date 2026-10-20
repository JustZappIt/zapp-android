package co.electriccoin.zcash.ui.screen.invest.gate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappCheckboxRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappConfirmationBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.component.zapp.ZappSelectionRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.invest.common.InvestScreenFrame

@Composable
internal fun InvestGateView(state: InvestGateState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Box(Modifier.fillMaxSize()) {
        InvestScreenFrame(
            title = stringResource(R.string.invest_gate_title),
            onBack = state.onBack,
            primaryButton = state.primaryButton,
        ) {
            BasicText(
                text = state.body.getValue(),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            Spacer(Modifier.height(spacing.xl2))
            CountryRow(state)
            state.suggestion?.let {
                Spacer(Modifier.height(spacing.md))
                BasicText(text = it.getValue(), style = ZappTheme.typography.caption.copy(color = c.textMuted))
            }
            Spacer(Modifier.height(spacing.lg))
            state.attestation?.let { CheckboxLine(it) }
            state.qualifiedInvestor?.let { CheckboxLine(it) }
            state.errorText?.let {
                Spacer(Modifier.height(spacing.md))
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.caption.copy(color = c.danger, fontWeight = FontWeight.Medium),
                )
            }
        }
        state.picker?.let { CountryPicker(it) }
    }
    ZappConfirmationBottomSheet(state.confirmation)
}

@Composable
private fun CheckboxLine(state: InvestCheckboxState) {
    ZappCheckboxRow(text = state.text.getValue(), isChecked = state.isChecked, onClick = state.onClick)
}

@Composable
private fun CountryRow(state: InvestGateState) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .clickable(onClick = state.onChangeCountry)
                .semantics { role = Role.Button }
                .padding(ZappTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                text = stringResource(R.string.invest_gate_country_label),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
            Spacer(Modifier.height(ZappTheme.spacing.xxs))
            BasicText(
                text = state.countryName ?: stringResource(R.string.invest_gate_country_none),
                style =
                    ZappTheme.typography.rowTitle.copy(
                        color = if (state.countryName == null) c.textMuted else c.text,
                    ),
            )
        }
        BasicText(
            text = stringResource(R.string.invest_gate_change),
            style = ZappTheme.typography.caption.copy(color = c.accentText, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** The searchable country list, drawn over the gate. */
@Composable
private fun CountryPicker(state: CountryPickerState) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    var field by remember { mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length))) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        ZappSectionLabel(
            text = stringResource(R.string.invest_gate_picker_title),
            modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.lg),
        )
        Box(Modifier.padding(horizontal = spacing.gutter)) {
            ZappInputField(
                value = field,
                onValueChange = {
                    field = it
                    state.onQueryChange(it.text)
                },
                placeholder = stringResource(R.string.invest_gate_picker_search),
            )
        }
        Spacer(Modifier.height(spacing.md))
        if (state.items.isEmpty()) {
            BasicText(
                text = stringResource(R.string.invest_gate_picker_empty),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
                modifier = Modifier.padding(spacing.gutter),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items = state.items, key = { it.code }) { item ->
                ZappSelectionRow(
                    title = item.name,
                    subtitle = null,
                    isSelected = item.isSelected,
                    onClick = item.onClick,
                )
                ZappRowDivider()
            }
        }
        ZappBottomActionBar(onBack = state.onBack)
    }
}

@PreviewScreens
@Composable
private fun PreviewGateRestricted() {
    ZcashTheme {
        InvestGateView(
            InvestGateState(
                body = stringRes("Tokenised stocks are only offered in some countries."),
                countryName = "Germany",
                suggestion = stringRes("We suggested Germany from your SIM."),
                attestation =
                    InvestCheckboxState(
                        stringRes("I live in Germany and I am not a US or Canadian person."),
                        isChecked = true,
                    ) {},
                qualifiedInvestor =
                    InvestCheckboxState(
                        stringRes("I am a qualified or professional investor under local rules."),
                        isChecked = false,
                    ) {},
                primaryButton = ButtonState(stringRes("Continue"), isEnabled = true),
                errorText = null,
                onChangeCountry = {},
                picker = null,
                onBack = {},
            ),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewPicker() {
    ZcashTheme {
        InvestGateView(
            InvestGateState(
                body = stringRes("Tokenised stocks are only offered in some countries."),
                countryName = "Indonesia",
                suggestion = null,
                attestation = null,
                qualifiedInvestor = null,
                primaryButton = ButtonState(stringRes("Continue"), isEnabled = false),
                errorText = null,
                onChangeCountry = {},
                picker =
                    CountryPickerState(
                        query = "In",
                        items =
                            listOf(
                                CountryItemState("IN", "India", isSelected = false) {},
                                CountryItemState("ID", "Indonesia", isSelected = true) {},
                            ),
                        onQueryChange = {},
                        onBack = {},
                    ),
                onBack = {},
            ),
        )
    }
}
