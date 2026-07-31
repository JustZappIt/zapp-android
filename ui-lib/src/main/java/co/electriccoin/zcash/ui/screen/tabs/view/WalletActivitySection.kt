package co.electriccoin.zcash.ui.screen.tabs.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.orHiddenString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityState
import co.electriccoin.zcash.ui.screen.transactionhistory.widget.ActivityWidgetState

internal fun LazyListScope.activitySection(
    state: ActivityWidgetState,
    showZecAsPrimary: Boolean = true,
) {
    when (state) {
        is ActivityWidgetState.Data -> {
            val lastKey = state.transactions.lastOrNull()?.key
            items(
                items = state.transactions,
                key = { it.key },
            ) { activity ->
                ActivityRow(activity, showZecAsPrimary = showZecAsPrimary)
                if (activity.key != lastKey) {
                    ZappRowDivider(inset = true)
                }
            }
            // Shown only when there are more activities than the home preview holds; opens the full list.
            state.header.button?.let { button ->
                item {
                    ZappRowDivider(inset = true)
                    SeeAllRow(button = button)
                }
            }
        }

        is ActivityWidgetState.Empty -> {
            item { ActivityEmpty() }
        }

        ActivityWidgetState.Loading -> {
            item { ActivityLoading() }
        }
    }
}

@Composable
private fun SeeAllRow(button: ButtonState) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = button.onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        BasicText(
            text = button.text.getValue(),
            style = ZappTheme.typography.body.copy(color = c.accentText),
        )
    }
}

@Composable
private fun ActivityRow(
    state: ActivityState,
    showZecAsPrimary: Boolean,
) {
    val c = ZappTheme.colors

    // Seeds the background swap-status poll for this row; without it the list shows stale status.
    LaunchedEffect(state.key) {
        state.onDisplayed()
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = state.onClick)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(c.surfaceAlt, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(state.bigIcon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = state.title.getValue(),
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.subtitle?.let { subtitle ->
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = subtitle.getValue(),
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (state.value != null || state.fiatValue != null) {
            ActivityValueColumn(state = state, showZecAsPrimary = showZecAsPrimary)
        }
    }
}

/**
 * Right-hand amount stack. The ZEC value and the fiat value swap primary/secondary roles to mirror
 * the headline balance card, which the user can toggle between fiat- and ZEC-first. Fiat can only
 * lead when it exists; otherwise ZEC always leads.
 */
@Composable
private fun ActivityValueColumn(
    state: ActivityState,
    showZecAsPrimary: Boolean,
) {
    val c = ZappTheme.colors
    val primaryStyle = ZappTheme.typography.rowTitle.copy(color = c.text)
    val secondaryStyle = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted)
    val fiatLeads = !showZecAsPrimary && state.fiatValue != null
    val hiddenValue = stringRes(co.electriccoin.zcash.ui.design.R.string.hide_balance_placeholder)

    Column(horizontalAlignment = Alignment.End) {
        if (fiatLeads) {
            BasicText(text = state.fiatValue orHiddenString hiddenValue, style = primaryStyle, maxLines = 1)
            state.value?.let { styled ->
                Spacer(Modifier.height(2.dp))
                BasicText(text = styled orHiddenString hiddenValue, style = secondaryStyle, maxLines = 1)
            }
        } else {
            state.value?.let { styled ->
                BasicText(text = styled orHiddenString hiddenValue, style = primaryStyle, maxLines = 1)
            }
            state.fiatValue?.let { fiat ->
                Spacer(Modifier.height(2.dp))
                BasicText(text = fiat orHiddenString hiddenValue, style = secondaryStyle, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ActivityEmpty() {
    val c = ZappTheme.colors
    // Swiss-style: left-aligned, no centered illustration, sharp top rule that
    // matches the divider rhythm an actual transaction list would have.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(BorderStroke(0.dp, c.border), RectangleShape)
                .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(c.accent, RectangleShape),
        )
        Spacer(Modifier.height(10.dp))
        BasicText(
            text = stringResource(R.string.home_activity_empty_title),
            style =
                ZappTheme.typography.rowTitle.copy(
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.3).sp,
                ),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = stringResource(R.string.home_activity_empty_subtitle),
            style =
                ZappTheme.typography.rowSubtitle.copy(
                    color = c.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
        )
    }
}

@Composable
private fun ActivityLoading() {
    val c = ZappTheme.colors
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = c.accent)
    }
}
