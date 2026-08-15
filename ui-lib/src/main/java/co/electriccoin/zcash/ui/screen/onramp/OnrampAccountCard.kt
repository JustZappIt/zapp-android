package co.electriccoin.zcash.ui.screen.onramp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ADDRESS_ELLIPSIS_PREFIX
import co.electriccoin.zcash.ui.design.component.zapp.ADDRESS_ELLIPSIS_SUFFIX
import co.electriccoin.zcash.ui.design.component.zapp.ZappExplorerLink
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import xyz.justzappit.offramp.onramp.OnrampDestination

@Composable
internal fun IntroCopy(destination: OnrampDestination) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
            text = stringResource(R.string.onramp_eyebrow).uppercase(),
            style = ZappTheme.typography.eyebrow.copy(color = ZappTheme.colors.accent),
        )
        BasicText(
            text = stringResource(R.string.onramp_headline),
            style = ZappTheme.typography.display.copy(color = ZappTheme.colors.text, fontWeight = FontWeight.Black),
        )
        BasicText(
            text =
                stringResource(
                    if (destination == OnrampDestination.ZCASH) {
                        R.string.onramp_zcash_subtitle
                    } else {
                        R.string.onramp_subtitle
                    },
                ),
            style = ZappTheme.typography.body.copy(color = ZappTheme.colors.textMuted),
        )
    }
}

/** Secondary account detail shown inside the existing information sheet. */
@Composable
internal fun OnrampDestinationInfo(state: OnrampState) {
    val uriHandler = LocalUriHandler.current
    val address = state.accountAddress ?: return
    val copyLabel = stringResource(R.string.onramp_copy_address)
    val colors = ZappTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            text =
                stringResource(
                    if (state.destination == OnrampDestination.ZCASH) {
                        R.string.onramp_refund_account_label
                    } else {
                        R.string.onramp_account_label
                    },
                ).uppercase(),
            style =
                ZappTheme.typography.eyebrow.copy(
                    color = colors.textMuted,
                    fontWeight = FontWeight.Medium,
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.addressExplorerUrl?.let { url ->
                Box(modifier = Modifier.weight(1f)) {
                    ZappExplorerLink(
                        value = address,
                        url = url,
                        prefix = ADDRESS_ELLIPSIS_PREFIX,
                        suffix = ADDRESS_ELLIPSIS_SUFFIX,
                        uriHandler = uriHandler,
                    )
                }
            } ?: BasicText(
                text = address,
                style = ZappTheme.typography.mono.copy(color = colors.text),
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = copyLabel,
                tint = colors.textMuted,
                modifier =
                    Modifier
                        .size(48.dp)
                        .clickable(onClick = state.onCopyAccountAddress)
                        .semantics { role = Role.Button }
                        .padding(15.dp),
            )
        }
        if (state.destination == OnrampDestination.ZCASH) {
            BasicText(
                text = stringResource(R.string.onramp_refund_account_explanation),
                style = ZappTheme.typography.caption.copy(color = colors.textMuted),
            )
        }
    }
}
