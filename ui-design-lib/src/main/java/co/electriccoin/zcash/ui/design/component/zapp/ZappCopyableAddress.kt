package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A labelled wallet address with a copy button, sized for a sheet that pads its own content.
 *
 * Shown whole rather than abridged: this stands in for a funds destination, and an abridged form
 * is what address poisoning relies on — a lookalike matches the head and tail a user checks and
 * differs only in the middle they never see.
 */
@Composable
fun ZappCopyableAddress(
    label: String,
    address: String,
    copyContentDescription: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    isCopied: Boolean = false,
    /** Enough for the whole address: a long one cut short isn't shown whole. */
    maxLines: Int = ADDRESS_MAX_LINES,
) {
    ZappValueCard(
        value = address,
        modifier = modifier,
        label = label,
        maxLines = maxLines,
        gutter = 0.dp,
        trailing = {
            ZappCopyIconButton(
                isCopied = isCopied,
                contentDescription = copyContentDescription,
                onClick = onCopy,
            )
        },
    )
}

private const val ADDRESS_MAX_LINES = 2
