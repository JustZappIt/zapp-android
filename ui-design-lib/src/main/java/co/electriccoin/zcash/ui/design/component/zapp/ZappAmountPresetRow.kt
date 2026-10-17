package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import java.math.BigInteger

/** A quick fill for an amount field: a share of what the screen can spend. */
@Suppress("MagicNumber") // The shares are what the buttons say.
enum class AmountPreset(
    val percent: Int
) {
    TWENTY(20),
    HALF(50),
    ALL(100);

    /** This share of [units], rounded down so it never asks for more than there is. */
    fun of(units: BigInteger): BigInteger =
        if (this == ALL) units else units * percent.toBigInteger() / ONE_HUNDRED

    fun of(units: Long): Long = of(units.toBigInteger()).toLong()
}

/**
 * [selected] stays lit until the amount is typed over. ALL fills the whole balance; the screen takes what the
 * payment costs out of it when it builds the payment, and its review shows the deduction.
 */
data class AmountPresetsState(
    val selected: AmountPreset? = null,
    val isEnabled: Boolean = true,
    val onSelect: (AmountPreset) -> Unit,
)

/** 20% / 50% / ALL, docked just above the screen's bottom bar so it rides the keyboard while the amount is typed. */
@Composable
fun ZappAmountPresetRow(
    state: AmountPresetsState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = ZappTheme.spacing.gutter, vertical = ZappTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZappTheme.spacing.sm),
    ) {
        AmountPreset.entries.forEach { preset ->
            PresetButton(
                label = preset.label(),
                description = preset.description(),
                isSelected = preset == state.selected,
                isEnabled = state.isEnabled,
                onClick = { state.onSelect(preset) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    description: String,
    isSelected: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET.dp)
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, if (isSelected) c.accent else c.border), RectangleShape)
                .clickable(enabled = isEnabled, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    role = Role.Button
                    selected = isSelected
                    if (!isEnabled) disabled()
                },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style =
                ZappTheme.typography.buttonSmall.copy(
                    color =
                        when {
                            !isEnabled -> c.textSubtle
                            isSelected -> c.accentText
                            else -> c.text
                        },
                ),
        )
    }
}

@Composable
private fun AmountPreset.label(): String =
    when (this) {
        AmountPreset.ALL -> stringResource(R.string.amount_preset_all)
        else -> stringResource(R.string.amount_preset_percent, percent)
    }

@Composable
private fun AmountPreset.description(): String =
    when (this) {
        AmountPreset.ALL -> stringResource(R.string.amount_preset_all_description)
        else -> stringResource(R.string.amount_preset_percent_description, percent)
    }

private const val PERCENT = 100L
private val ONE_HUNDRED = BigInteger.valueOf(PERCENT)
private const val MIN_TOUCH_TARGET = 48
