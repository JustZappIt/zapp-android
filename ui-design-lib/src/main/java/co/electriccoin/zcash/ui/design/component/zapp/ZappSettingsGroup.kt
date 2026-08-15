package co.electriccoin.zcash.ui.design.component.zapp

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Section label, a bordered column of [ZappRow]s, and an optional explanatory footer — the shape
 * every settings-style list on the Zapp tabs and detail screens uses.
 */
@Composable
fun ZappSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes titleLogo: Int? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ZappTheme.colors
    Column(modifier = modifier) {
        if (titleLogo == null) {
            ZappGroupHeader(text = title)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = TEXT_GUTTER, top = 16.dp, bottom = 6.dp),
            ) {
                Image(
                    painter = painterResource(titleLogo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(TITLE_LOGO_HEIGHT),
                )
                ZappSectionLabel(text = title)
            }
        }
        ZappBorderedCard(
            modifier = Modifier.padding(horizontal = GUTTER),
            padding = 0.dp,
            content = content,
        )
        if (footer != null) {
            BasicText(
                text = footer,
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
                modifier = Modifier.padding(start = TEXT_GUTTER, end = TEXT_GUTTER, top = 8.dp),
            )
        }
    }
}

private val GUTTER = 14.dp
private val TEXT_GUTTER = 18.dp
private val TITLE_LOGO_HEIGHT = 14.dp
