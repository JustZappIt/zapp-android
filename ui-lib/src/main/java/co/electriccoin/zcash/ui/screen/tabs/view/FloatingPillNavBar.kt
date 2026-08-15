package co.electriccoin.zcash.ui.screen.tabs.view

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Payment
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.theme.ZappTheme

internal enum class ZappTab(
    @param:StringRes val titleRes: Int,
    val testTag: String,
) {
    PAY(R.string.home_pay_title, PAY_TAB_TEST_TAG),
    CHATS(R.string.chat_list_title, CHATS_TAB_TEST_TAG),
    YOU(R.string.settings_you_title, YOU_TAB_TEST_TAG),
}

@Composable
internal fun FloatingPillNavBar(
    currentTab: ZappTab,
    chatUnreadCount: Int,
    onTabSelected: (ZappTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val haptic = LocalHapticFeedback.current

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth(0.81f)
                    .shadow(elevation = 4.dp, shape = RectangleShape, clip = false)
                    .background(c.navPill, RectangleShape)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .padding(horizontal = 5.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZappTab.entries.forEach { tab ->
                val selected = tab == currentTab
                val icon: ImageVector = iconFor(tab, selected)
                val showBadge = tab == ZappTab.CHATS && chatUnreadCount > 0
                val label = stringResource(tab.titleRes)
                val cellBg by
                    animateColorAsState(
                        targetValue = if (selected) c.accent else Color.Transparent,
                        animationSpec = tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                        label = "navCellBg",
                    )
                val iconTint by
                    animateColorAsState(
                        targetValue = if (selected) c.onAccent else c.textMuted,
                        animationSpec = tween(ZappMotion.CONTENT_MS, easing = ZappMotion.easing),
                        label = "navIconTint",
                    )

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag(tab.testTag)
                            .defaultMinSize(minHeight = 48.dp)
                            .background(color = cellBg, shape = RectangleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication =
                                    ripple(
                                        color = if (selected) c.onAccent else c.accent,
                                        bounded = true,
                                    ),
                                onClick = {
                                    if (tab != currentTab) {
                                        runCatching {
                                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        }
                                    }
                                    onTabSelected(tab)
                                },
                            ).semantics {
                                contentDescription = label
                                role = Role.Tab
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp),
                    )

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showBadge,
                        modifier = Modifier.align(Alignment.TopEnd),
                        enter =
                            scaleIn(tween(ZappMotion.STATE_MS, easing = ZappMotion.easing)) +
                                fadeIn(tween(ZappMotion.STATE_MS)),
                        exit =
                            scaleOut(tween(ZappMotion.STATE_MS, easing = ZappMotion.easing)) +
                                fadeOut(tween(ZappMotion.STATE_MS)),
                    ) {
                        // coerceAtLeast keeps "0" from flashing while the badge scales out.
                        val badgeText =
                            if (chatUnreadCount > 99) "99+" else chatUnreadCount.coerceAtLeast(1).toString()
                        Box(
                            modifier =
                                Modifier
                                    .offset(x = (-6).dp, y = 4.dp)
                                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                    .background(c.danger, RectangleShape)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                text = badgeText,
                                style =
                                    ZappTheme.typography.chip.copy(
                                        color = c.onAccent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun iconFor(
    tab: ZappTab,
    selected: Boolean,
): ImageVector =
    when (tab) {
        ZappTab.PAY -> if (selected) Icons.Filled.Payment else Icons.Outlined.Payment
        ZappTab.CHATS -> if (selected) Icons.AutoMirrored.Filled.Chat else Icons.AutoMirrored.Outlined.Chat
        ZappTab.YOU -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
    }

private const val PAY_TAB_TEST_TAG = "zapp_tab_pay"
private const val CHATS_TAB_TEST_TAG = "zapp_tab_chats"
private const val YOU_TAB_TEST_TAG = "zapp_tab_you"
