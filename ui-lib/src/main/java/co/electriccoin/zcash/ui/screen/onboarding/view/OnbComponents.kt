package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.animation.pressScale
import co.electriccoin.zcash.ui.design.theme.ZappTheme

/**
 * Five segmented progress bar pinned to the top of every onboarding step
 * (mirrors `OnbProgress` in the design's React reference).
 */
@Composable
internal fun OnbProgress(
    step: Int,
    total: Int = 3,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(total) { i ->
            val fill by
                animateColorAsState(
                    targetValue = if (i < step) c.accent else c.border,
                    animationSpec = tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                    label = "onbProgressFill",
                )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(fill, RectangleShape),
            )
        }
    }
}

/**
 * Oversized ghosted step number painted in the surfaceAlt color so it sits
 * behind the foreground hero text. Matches `GhostNum` in the design.
 */
@Composable
internal fun GhostNum(n: Int, modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    BasicText(
        text = String.format("%02d", n),
        modifier = modifier,
        style =
            ZappTheme.typography.display.copy(
                color = c.surfaceAlt,
                fontSize = 130.sp,
                lineHeight = 130.sp,
                letterSpacing = (-6).sp,
                fontWeight = FontWeight.Black,
            ),
    )
}

/** Tiny eyebrow label — uppercase, accent color, wide tracking. */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    BasicText(
        text = text.uppercase(),
        modifier = modifier,
        style =
            ZappTheme.typography.eyebrow.copy(
                color = c.accent,
                fontSize = 10.sp,
                letterSpacing = 2.5.sp,
                fontWeight = FontWeight.Black,
            ),
    )
}

/** 36×3 accent rule. */
@Composable
internal fun AccentRule(modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    Box(
        modifier =
            modifier
                .width(36.dp)
                .height(3.dp)
                .background(c.text, RectangleShape),
    )
}

/**
 * Bottom dock with optional Back chevron on the left and primary CTA on the
 * right. Mirrors `BottomDock` in the design.
 */
@Composable
internal fun OnbBottomDock(
    cta: String,
    onCta: () -> Unit,
    modifier: Modifier = Modifier,
    ctaEnabled: Boolean = true,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    noBorder: Boolean = false,
    showCta: Boolean = true,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp)
                .padding(bottom = 8.dp)
                .let { if (noBorder) it else it.border(1.dp, c.text, RectangleShape) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                Box(
                    modifier =
                        Modifier
                            .height(52.dp)
                            .let { if (showCta) it.width(72.dp) else it.fillMaxWidth() }
                            .clickable(onClick = onBack)
                            .border(1.dp, c.border, RectangleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(
                            text = "←",
                            style =
                                ZappTheme.typography.button.copy(
                                    color = c.text,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp,
                                ),
                        )
                        if (!showCta) {
                            BasicText(
                                text = stringResource(R.string.onboarding_back_label),
                                style =
                                    ZappTheme.typography.button.copy(
                                        color = c.textSubtle,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 15.sp,
                                    ),
                            )
                        }
                    }
                }
            }
            if (showCta) {
                val bg by
                    animateColorAsState(
                        targetValue = if (ctaEnabled) c.accent else c.surfaceAlt,
                        animationSpec = tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                        label = "onbCtaBg",
                    )
                val fg by
                    animateColorAsState(
                        targetValue = if (ctaEnabled) c.onAccent else c.textSubtle,
                        animationSpec = tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                        label = "onbCtaFg",
                    )
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .pressScale(interactionSource)
                            .background(bg, RectangleShape)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = ripple(color = c.onAccent, bounded = true),
                                enabled = ctaEnabled,
                                onClick = onCta,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = cta,
                        style =
                            ZappTheme.typography.button.copy(
                                color = fg,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.2.sp,
                            ),
                    )
                }
            }
        }
    }
}

/** Big bold hero title — 42sp Black, used on most onboarding screens. */
@Composable
internal fun OnbHero(text: String, modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    BasicText(
        text = text,
        modifier = modifier,
        style =
            ZappTheme.typography.display.copy(
                color = c.text,
                fontSize = 42.sp,
                lineHeight = 44.sp,
                letterSpacing = (-1.8).sp,
                fontWeight = FontWeight.Black,
            ),
    )
}

/** Subtitle text under hero — 13sp muted. */
@Composable
internal fun OnbSub(text: String, modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    BasicText(
        text = text,
        modifier = modifier,
        style =
            ZappTheme.typography.body.copy(
                color = c.textMuted,
                fontSize = 13.sp,
                lineHeight = 22.sp,
            ),
    )
}

/**
 * One row inside an [OnbActionListCard]. Highlighted rows render with the
 * accentSoft background and an accent-coloured leading icon (the design's
 * "primary" treatment — see `OnbWallet` in the React reference).
 */
data class OnbAction(
    val icon: String,
    val label: String,
    val sub: String,
    val onClick: () -> Unit,
    val highlight: Boolean = false,
)

/**
 * Sharp-edged stacked list of tappable actions, separated by 1dp dividers and
 * wrapped in a 1dp border. Reused by the onboarding wallet-choice step and the
 * wallet-tab empty state so both surfaces look identical.
 */
@Composable
internal fun OnbActionListCard(
    actions: List<OnbAction>,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, c.border, RectangleShape),
    ) {
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(c.border, RectangleShape),
                )
            }
            OnbActionRow(action = action, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Single tappable action row: icon box on the left, label + sub stacked in the
 * middle, chevron on the right. Highlighted rows use accentSoft background and
 * an accent-coloured icon box. Lives outside [OnbActionListCard] so standalone
 * tiles (e.g. the 2FA choice screen) can render the same row treatment without
 * the surrounding list-card border.
 */
@Composable
internal fun OnbActionRow(
    action: OnbAction,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .background(if (action.highlight) c.accentSoft else c.bg, RectangleShape)
                .clickable(onClick = action.onClick)
                .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(36.dp)
                    .height(36.dp)
                    .background(if (action.highlight) c.accent else c.surfaceAlt, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = action.icon,
                style =
                    ZappTheme.typography.body.copy(
                        color = if (action.highlight) c.onAccent else c.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = action.label,
                style =
                    ZappTheme.typography.rowTitle.copy(
                        color = c.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = action.sub,
                style =
                    ZappTheme.typography.rowSubtitle.copy(
                        color = c.textMuted,
                        fontSize = 12.sp,
                    ),
            )
        }
        BasicText(
            text = "›",
            style =
                ZappTheme.typography.body.copy(
                    color = c.textSubtle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                ),
        )
    }
}

/**
 * Full-width row with a 3dp accent stripe on the left and a label/sub block.
 * Used in phase intros to list the points the user is about to cover.
 */
@Composable
internal fun OnbBulletRow(
    label: String,
    sub: String? = null,
    isFirst: Boolean = false,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .let { if (isFirst) it.border(1.dp, c.border, RectangleShape) else it }
                .padding(top = 14.dp, bottom = 14.dp, end = 14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .height(38.dp)
                    .background(c.accent, RectangleShape),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            BasicText(
                text = label,
                style =
                    ZappTheme.typography.rowTitle.copy(
                        color = c.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.2).sp,
                    ),
            )
            if (sub != null) {
                Spacer(Modifier.height(3.dp))
                BasicText(
                    text = sub,
                    style =
                        ZappTheme.typography.rowSubtitle.copy(
                            color = c.textMuted,
                            fontSize = 12.sp,
                        ),
                )
            }
        }
    }
}
