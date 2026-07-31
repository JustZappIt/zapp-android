package co.electriccoin.zcash.ui.screen.topup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.rememberScreenModalBottomSheetState
import co.electriccoin.zcash.ui.design.theme.ZappTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopUpView(
    state: TopUpState?,
    sheetState: SheetState = rememberScreenModalBottomSheetState(),
) {
    val c = ZappTheme.colors
    ZashiScreenModalBottomSheet(
        state = state,
        sheetState = sheetState,
        containerColor = c.surface,
        content = { s, contentPadding -> Content(state = s, contentPadding = contentPadding) },
    )
}

@Composable
private fun Content(
    state: TopUpState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xl3)
                .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        BasicText(
            text = stringResource(R.string.top_up_title),
            style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.Black),
        )
        Spacer(Modifier.height(spacing.xs))
        BasicText(
            text = stringResource(R.string.top_up_subtitle),
            style = ZappTheme.typography.body.copy(color = c.textMuted),
        )
        Spacer(Modifier.height(spacing.xl))
        TopUpSourceRow(
            icon = Icons.Default.AccountBalance,
            title = stringResource(R.string.top_up_from_exchange_title),
            subtitle = stringResource(R.string.top_up_from_exchange_subtitle),
            onClick = state.onFromExchange,
        )
        Spacer(Modifier.height(spacing.md))
        TopUpSourceRow(
            icon = Icons.Default.AccountBalanceWallet,
            title = stringResource(R.string.top_up_from_wallet_title),
            subtitle = stringResource(R.string.top_up_from_wallet_subtitle),
            onClick = state.onFromWallet,
        )
    }
}

@Composable
private fun TopUpSourceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val c = ZappTheme.colors
    val spacing = ZappTheme.spacing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, c.border, RectangleShape)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {
                    this.role = Role.Button
                    contentDescription = "$title. $subtitle"
                }.padding(spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xl),
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .background(c.accentSoft, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = c.accentText,
                modifier = Modifier.size(spacing.xl2),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
            )
            Spacer(Modifier.height(spacing.xxs))
            BasicText(
                text = subtitle,
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
            )
        }
    }
}
