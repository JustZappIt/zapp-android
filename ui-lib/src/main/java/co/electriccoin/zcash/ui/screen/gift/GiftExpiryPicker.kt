// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.gift

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * The expiry control, deliberately small.
 *
 * Expiry is advisory: nothing on chain enforces it, the link still claims after the date, and no
 * funds are destroyed by one passing — so setting one buys almost nothing, and a card with no expiry
 * is the right default for nearly everyone. Giving it a full section with a segmented selector
 * advertised it as a decision worth making. It is one quiet line instead, and the caveat only
 * appears once somebody has actually set a date.
 */
@Composable
internal fun GiftExpiryPicker(
    expiry: GiftExpiry,
    enabled: Boolean,
    onExpiryChange: (GiftExpiry) -> Unit,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.gift_card_expiry_label)

    Column {
        Row(
            modifier =
                Modifier
                    .clickable(enabled = enabled) { expanded = true }
                    .semantics {
                        role = Role.DropdownList
                        contentDescription = label
                    }.padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = stringResource(R.string.gift_card_expiry_compact, expiry.label()),
                style = ZappTheme.typography.caption.copy(color = c.textSubtle),
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = c.textSubtle,
                modifier = Modifier.size(spacing.xl2),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RectangleShape,
            containerColor = c.surface,
        ) {
            GiftExpiry.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        BasicText(
                            text = option.label(),
                            style =
                                ZappTheme.typography.rowSubtitle
                                    .copy(color = if (option == expiry) c.accentText else c.text),
                        )
                    },
                    onClick = {
                        expanded = false
                        onExpiryChange(option)
                    },
                )
            }
        }

        // Only once a date exists is there anything to caveat. Shown here rather than on the review
        // screen because this is where the sender can still change their mind for free.
        if (expiry != GiftExpiry.NEVER) {
            BasicText(
                text = stringResource(R.string.gift_card_expiry_hint),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun GiftExpiry.label(): String =
    days?.let { stringResource(R.string.gift_card_expiry_days, it) }
        ?: stringResource(R.string.gift_card_expiry_none)
