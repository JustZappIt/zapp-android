package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.animation.pressScale
import co.electriccoin.zcash.ui.design.theme.ZappTheme

// Shared string helpers used by the list + profile components.

/** Extract up to two uppercase initials from a display name; falls back to
 *  the first two characters if the name contains no word separators. */
fun initialsOf(name: String): String =
    name
        .split(' ', '_', '-', '.')
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifBlank { name.take(2).uppercase() }

/** Shorten a long key / address to `head…tail`. */
fun String.ellipsizeAddress(head: Int = 10, tail: Int = 6): String =
    if (length <= head + tail + 1) this else "${take(head)}…${takeLast(tail)}"

@Composable
fun ZappScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
    left: (@Composable () -> Unit)? = null,
    titleStyle: TextStyle = ZappTheme.typography.screenTitle,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(c.bg)
                // Respect horizontal display cutouts (camera punch-holes) so the right-side chip
                // is never obscured behind the camera or a system overlay.
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (left != null) left()
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .then(
                        if (onTitleClick != null) {
                            Modifier.clickable(
                                indication = ripple(bounded = true),
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onTitleClick,
                            )
                        } else {
                            Modifier
                        },
                    ),
        ) {
            BasicText(
                text = title,
                style = titleStyle.copy(color = c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = subtitle,
                    style = ZappTheme.typography.caption.copy(color = c.textMuted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (right != null) right()
    }
}

@Composable
fun ZappSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val c = ZappTheme.colors
    BasicText(
        text = text.uppercase(),
        style = ZappTheme.typography.groupLabel.copy(color = color ?: c.textMuted),
        modifier = modifier,
    )
}

enum class ZappChipVariant { Muted, Success, Accent, Danger }

@Composable
fun ZappStatusChip(
    text: String,
    modifier: Modifier = Modifier,
    variant: ZappChipVariant = ZappChipVariant.Muted,
    dotColor: Color? = null,
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = ZappTheme.colors
    val (bg, fg) =
        when (variant) {
            ZappChipVariant.Success -> c.successSoft to c.success
            ZappChipVariant.Accent -> c.accentSoft to c.accentText
            ZappChipVariant.Danger -> c.dangerSoft to c.danger
            ZappChipVariant.Muted -> c.chipBg to c.textMuted
        }
    val base =
        modifier
            .background(bg, RectangleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    Row(
        modifier = base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dotColor != null) {
            Box(modifier = Modifier.size(6.dp).background(dotColor, RectangleShape))
        }
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(12.dp),
            )
        }
        BasicText(text = text, style = ZappTheme.typography.chip.copy(color = fg))
    }
}

@Composable
fun ZappRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    iconBackground: Color? = null,
    titleColor: Color? = null,
    trailing: (@Composable () -> Unit)? = { ZappRowChevron() },
    onClick: (() -> Unit)? = null,
) {
    val c = ZappTheme.colors
    val base = modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
    val withClick =
        if (onClick != null) {
            base.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = c.accent),
                onClick = onClick,
            )
        } else {
            base
        }

    Row(
        modifier = withClick.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .background(iconBackground ?: c.surfaceAlt, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint ?: c.text,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = ZappTheme.typography.rowTitle.copy(color = titleColor ?: c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = subtitle,
                    style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailing != null) trailing()
    }
}

@Composable
fun ZappSelectionRow(
    title: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledTrailing: (@Composable () -> Unit)? = null,
) {
    val c = ZappTheme.colors
    ZappRow(
        title = title,
        subtitle = subtitle,
        titleColor =
            when {
                !enabled -> c.textMuted
                isSelected -> c.accentText
                else -> null
            },
        trailing =
            when {
                !enabled -> {
                    disabledTrailing
                }

                isSelected -> {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = c.accentText,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                else -> {
                    null
                }
            },
        onClick = onClick.takeIf { enabled },
        modifier = modifier,
    )
}

@Composable
private fun ZappRowChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = ZappTheme.colors.textSubtle,
        modifier = Modifier.size(18.dp),
    )
}

/** Hairline row divider, inset under the row title column when [inset] is true. */
@Composable
fun ZappRowDivider(
    modifier: Modifier = Modifier,
    inset: Boolean = false,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = if (inset) 68.dp else 18.dp, end = 18.dp)
                .height(1.dp)
                .background(ZappTheme.colors.border),
    )
}

enum class ZappButtonVariant { Primary, Secondary, Ghost, Danger, AccentGhost }

@Composable
fun ZappButton(
    text: String,
    modifier: Modifier = Modifier,
    variant: ZappButtonVariant = ZappButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val (bg, fg, borderCol) = zappButtonPalette(variant, enabled)

    val animatedBg by
        animateColorAsState(
            targetValue = bg,
            animationSpec = tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
            label = "zappButtonBg",
        )
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = 52.dp)
                .pressScale(interactionSource)
                .background(animatedBg, RectangleShape)
                .then(
                    if (borderCol != null) {
                        Modifier.border(BorderStroke(1.dp, borderCol), RectangleShape)
                    } else {
                        Modifier
                    },
                ).alpha(if (enabled || variant == ZappButtonVariant.Primary) 1f else 0.45f)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = ripple(color = fg),
                    onClick = onClick,
                ).semantics(mergeDescendants = true) {
                    this.contentDescription = text
                    this.role = Role.Button
                    if (!enabled) disabled()
                }.padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A withdrawal waits on a bundler receipt, which is minutes rather than seconds. A
            // greyed-out button alone reads as "nothing happened", so the wait is shown as one.
            if (loading) {
                CircularProgressIndicator(
                    color = fg,
                    strokeWidth = BUTTON_PROGRESS_STROKE.dp,
                    modifier = Modifier.size(BUTTON_PROGRESS_SIZE.dp),
                )
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(18.dp),
                )
            }
            BasicText(text = text, style = ZappTheme.typography.button.copy(color = fg))
        }
    }
}

private const val BUTTON_PROGRESS_SIZE = 16
private const val BUTTON_PROGRESS_STROKE = 2

// Primary disabled: surfaceAlt bg + textSubtle text per design system.
// Other variants dim with alpha when disabled.
@Composable
private fun zappButtonPalette(variant: ZappButtonVariant, enabled: Boolean): Triple<Color, Color, Color?> {
    val c = ZappTheme.colors
    if (variant == ZappButtonVariant.Primary && !enabled) return Triple(c.surfaceAlt, c.textSubtle, null)
    return when (variant) {
        ZappButtonVariant.Primary -> Triple(c.accent, c.onAccent, null)
        ZappButtonVariant.Secondary -> Triple(c.surfaceAlt, c.text, null)
        ZappButtonVariant.Ghost -> Triple(Color.Transparent, c.text, c.border)
        ZappButtonVariant.Danger -> Triple(c.dangerSoft, c.danger, null)
        ZappButtonVariant.AccentGhost -> Triple(Color.Transparent, c.accent, c.accent)
    }
}

@Composable
fun ZappToggle(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Box(
        modifier =
            modifier
                .size(width = 42.dp, height = 24.dp)
                .background(if (checked) c.accent else c.borderStrong, RectangleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .padding(start = if (checked) 20.dp else 2.dp, top = 2.dp)
                    .size(20.dp)
                    .background(Color.White, RectangleShape),
        )
    }
}

/** Uppercase group header for a card-stacked settings section. */
@Composable
fun ZappGroupHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    ZappSectionLabel(
        text = text,
        modifier = modifier.padding(start = 18.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** Flat orange square FAB anchored above the floating nav pill. */
@Composable
fun ZappFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 56,
) {
    val c = ZappTheme.colors
    Box(
        modifier =
            modifier
                .size(size.dp)
                .shadow(elevation = 4.dp, shape = RectangleShape, clip = false)
                .background(c.accent, RectangleShape)
                .border(BorderStroke(1.dp, c.accentBorder), RectangleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = c.onAccent, bounded = true),
                    onClick = onClick,
                ).semantics(mergeDescendants = true) { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = c.onAccent,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Bottom action bar for detail/sub-screens.
 *
 * The back button is always on the LEFT (thumb-reachable). An optional
 * [primaryAction] — typically a [ZappButton] — sits on the RIGHT, horizontally
 * aligned with the back button. Respects system navigation bar insets.
 *
 * The content row holds a 52dp min height (the [ZappButton] height) so the bar
 * measures the same with or without a primary action. The surface/border panel
 * chrome only appears when there is a primary action; a lone back button
 * renders chrome-free.
 *
 * Usage: pass this as the `bottomBar` slot of a [androidx.compose.material3.Scaffold].
 */
@Composable
fun ZappBottomActionBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isBackEnabled: Boolean = true,
    primaryAction: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp)
                .padding(bottom = 8.dp)
                .then(
                    if (primaryAction != null) {
                        Modifier
                            .background(c.surface)
                            .border(BorderStroke(1.dp, c.border), RectangleShape)
                    } else {
                        Modifier
                    },
                ).padding(12.dp)
                .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ZappBackButton(onClick = onBack, enabled = isBackEnabled)
        if (primaryAction != null) primaryAction()
    }
}

/** 48-dp touch target (Google Play minimum). Visual arrow is 20dp, centered. */
@Composable
fun ZappBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = ZappTheme.colors
    val backContentDescription = stringResource(R.string.general_back_content_description)
    Box(
        modifier =
            modifier
                .size(48.dp)
                .clickable(
                    enabled = enabled,
                    onClick = onClick,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = c.text),
                ).semantics {
                    contentDescription = backContentDescription
                    role = Role.Button
                    if (!enabled) disabled()
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = if (enabled) c.text else c.textSubtle,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Flat, bordered tile that stacks an icon tile over a label. Used in row
 * layouts (Send / Receive / Scan) where each tile takes equal weight.
 */
@Composable
fun ZappActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .defaultMinSize(minHeight = 96.dp)
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .alpha(if (enabled) 1f else 0.45f)
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = c.accent),
                    onClick = onClick,
                ).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .background(c.accentSoft, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = c.accentText,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        BasicText(
            text = label,
            style = ZappTheme.typography.rowTitle.copy(color = c.text),
        )
    }
}
