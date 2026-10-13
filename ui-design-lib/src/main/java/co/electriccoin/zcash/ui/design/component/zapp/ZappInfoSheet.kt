package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/** How a flow works, opened from its [ZappInfoButton]: numbered steps, then notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZappInfoSheet(
    title: String,
    steps: List<String>,
    notes: List<String>,
    onDismiss: () -> Unit,
    titleDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    ZashiScreenModalBottomSheet(onDismissRequest = onDismiss) { padding ->
        // weight(1f, false): content taller than the sheet scrolls, so OK stays reachable at any font size.
        Column(
            modifier =
                Modifier
                    .weight(1f, false)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = SHEET_GUTTER.dp,
                        end = SHEET_GUTTER.dp,
                        bottom = padding.calculateBottomPadding(),
                    ),
            verticalArrangement = Arrangement.spacedBy(INFO_GAP.dp),
        ) {
            BasicText(
                text = title,
                style = ZappTheme.typography.sectionTitle.copy(color = ZappTheme.colors.text),
                modifier =
                    Modifier.semantics {
                        heading()
                        titleDescription?.let { contentDescription = it }
                    },
            )
            steps.forEachIndexed { index, step -> Step(index + 1, step) }
            notes.forEach {
                BasicText(it, style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted))
            }
            content()
            ZappButton(
                text = stringResource(R.string.general_ok),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Step(
    index: Int,
    text: String
) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(INFO_GAP.dp),
    ) {
        Box(
            modifier = Modifier.size(STEP_BADGE.dp).background(ZappTheme.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                index.toString(),
                style =
                    ZappTheme.typography.caption.copy(
                        color = ZappTheme.colors.accentText,
                        fontWeight = FontWeight.Black,
                    ),
            )
        }
        BasicText(text, style = ZappTheme.typography.body.copy(color = ZappTheme.colors.text))
    }
}

private const val SHEET_GUTTER = 24
private const val INFO_GAP = 12
private const val STEP_BADGE = 20
