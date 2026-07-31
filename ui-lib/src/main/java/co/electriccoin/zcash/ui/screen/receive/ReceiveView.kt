package co.electriccoin.zcash.ui.screen.receive

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.appbar.ZashiMainTopAppBarState
import co.electriccoin.zcash.ui.design.animation.ZappMotion
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.styledStringResource
import co.electriccoin.zcash.ui.fixture.ZashiMainTopAppBarStateFixture
import kotlinx.coroutines.delay

@Composable
internal fun ReceiveView(
    state: ReceiveState,
    appBarState: ZashiMainTopAppBarState?,
) {
    when {
        state.items.isNullOrEmpty() && state.isLoading -> {
            CircularScreenProgressIndicator()
        }

        else -> {
            val c = ZappTheme.colors
            val items = state.items.orEmpty()
            val selectedIndex = items.indexOfFirst { it.isExpanded }.coerceAtLeast(0)
            val selectedItem = items.getOrNull(selectedIndex) ?: return

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(c.bg)
                        .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                ZappScreenHeader(title = stringResource(R.string.receive_title))

                ReceiveMainPanel(
                    selectedItem = selectedItem,
                    modifier = Modifier.weight(1f),
                )

                if (items.size > 1) {
                    ReceiveTabSwitcher(
                        items = items,
                        selectedIndex = selectedIndex,
                        onSelect = { idx -> items.getOrNull(idx)?.onClick?.invoke() },
                    )
                }

                ReceiveShareButton(
                    addressRaw = selectedItem.addressRaw,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .padding(bottom = 8.dp),
                )

                ReceiveBottomDock(
                    onBack = state.onBack,
                    onRequest = selectedItem.onRequestClicked,
                )
            }
        }
    }
}

@Composable
private fun ReceiveMainPanel(
    selectedItem: ReceiveAddressState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))

        Box(
            modifier =
                Modifier
                    .background(c.bg, RectangleShape)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            ZashiQr(
                state =
                    QrState(
                        qrData = selectedItem.qrData,
                        centerImage = selectedItem.icon,
                    ),
                modifier = Modifier.fillMaxWidth(0.92f),
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = selectedItem.subtitle.getValue(),
                style =
                    ZappTheme.typography.mono.copy(
                        color = c.textMuted,
                        fontSize = 12.sp,
                    ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            CopyIconButton(onClick = selectedItem.onCopyClicked)
        }

        Spacer(Modifier.height(20.dp))

        // Swiss tip block
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .height(36.dp)
                        .background(c.accent, RectangleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = stringResource(R.string.receive_tip_label),
                    style =
                        ZappTheme.typography.eyebrow.copy(
                            color = c.textSubtle,
                            fontSize = 10.sp,
                            letterSpacing = 1.8.sp,
                            fontWeight = FontWeight.Black,
                        ),
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = stringResource(id = R.string.receive_prioritize_shielded),
                    style =
                        ZappTheme.typography.body.copy(
                            color = c.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        ),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReceiveShareButton(
    addressRaw: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = ZappTheme.colors
    Box(
        modifier =
            modifier
                .height(48.dp)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .clickable {
                    val intent =
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, addressRaw)
                        }
                    context.startActivity(android.content.Intent.createChooser(intent, null))
                }.semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = stringResource(R.string.receive_share).uppercase(),
            style =
                ZappTheme.typography.button.copy(
                    color = c.textMuted,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.4.sp,
                ),
        )
    }
}

@Composable
private fun ReceiveBottomDock(
    onBack: () -> Unit,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val backContentDescription = stringResource(R.string.receive_back_content_description)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp)
                .padding(bottom = 8.dp)
                .background(c.surface)
                .border(BorderStroke(1.dp, c.border), RectangleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 72.dp, height = 52.dp)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .clickable(onClick = onBack)
                    .semantics {
                        contentDescription = backContentDescription
                        role = Role.Button
                    },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = stringResource(R.string.receive_back_glyph),
                style =
                    ZappTheme.typography.button.copy(
                        color = c.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .background(c.accent, RectangleShape)
                    .clickable(onClick = onRequest)
                    .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = stringResource(R.string.receive_request).uppercase(),
                style =
                    ZappTheme.typography.button.copy(
                        color = c.onAccent,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.6.sp,
                    ),
            )
        }
    }
}

@Composable
private fun ReceiveTabSwitcher(
    items: List<ReceiveAddressState>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = index == selectedIndex
            val label =
                if (item.isShielded) {
                    stringResource(R.string.receive_tab_shielded)
                } else {
                    stringResource(R.string.receive_tab_transparent)
                }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 34.dp)
                        .background(
                            if (isSelected) c.accent else androidx.compose.ui.graphics.Color.Transparent,
                            RectangleShape,
                        ).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = c.accent),
                            onClick = { onSelect(index) },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = label,
                    style =
                        ZappTheme.typography.caption.copy(
                            color = if (isSelected) c.onAccent else c.textMuted,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                        ),
                )
            }
        }
    }
}

@Composable
private fun CopyIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val haptic = LocalHapticFeedback.current
    val copyContentDescription = stringResource(R.string.receive_copy)
    // Inline ✓ confirmation: the system clipboard chip is not shown on non-admin
    // profiles (see CopyToClipboardUseCase), so the button itself must confirm.
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_CONFIRM_MS)
            copied = false
        }
    }
    val borderColor by
        animateColorAsState(
            targetValue = if (copied) c.success else c.border,
            animationSpec = tween(ZappMotion.STATE_MS, easing = ZappMotion.easing),
            label = "copyBorder",
        )
    Box(
        modifier =
            modifier
                .size(40.dp)
                .border(BorderStroke(1.dp, borderColor), RectangleShape)
                .clickable(onClick = {
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.ContextClick) }
                    copied = true
                    onClick()
                })
                .semantics {
                    role = Role.Button
                    contentDescription = copyContentDescription
                },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = copied,
            animationSpec = tween(ZappMotion.STATE_MS, easing = ZappMotion.easing),
            label = "copyIcon",
        ) { showCheck ->
            if (showCheck) {
                BasicText(
                    text = "✓",
                    style =
                        ZappTheme.typography.chip.copy(
                            color = c.success,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                        ),
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_copy_shielded),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    colorFilter = ColorFilter.tint(c.accentText),
                )
            }
        }
    }
}

private const val COPY_CONFIRM_MS = 1_500L

@Composable
@PreviewScreens
private fun LoadingPreview() =
    ZcashTheme(forceDarkMode = true) {
        ReceiveView(
            state =
                ReceiveState(
                    items = null,
                    isLoading = true,
                    onBack = {}
                ),
            appBarState = ZashiMainTopAppBarStateFixture.new()
        )
    }

@PreviewScreens
@Composable
private fun ZashiPreview() =
    ZcashTheme {
        ReceiveView(
            state =
                ReceiveState(
                    items =
                        listOf(
                            ReceiveAddressState(
                                icon = R.drawable.ic_zec_round_full,
                                title = stringRes("Zapp"),
                                subtitle = styledStringResource("u1sscfn3f3...83ruj7gfcd"),
                                isShielded = true,
                                onCopyClicked = {},
                                onQrClicked = { },
                                onRequestClicked = {},
                                isExpanded = true,
                                onClick = {},
                                colorMode = ReceiveAddressState.ColorMode.ZASHI,
                                infoIconButton =
                                    IconButtonState(
                                        R.drawable.ic_receive_zashi_shielded_info,
                                        onClick = {}
                                    ),
                                qrData = "u1sscfn3f3abcdef83ruj7gfcd",
                                addressRaw = "u1sscfn3f3abcdef83ruj7gfcd",
                            ),
                            ReceiveAddressState(
                                icon = R.drawable.ic_zec_round_full,
                                title = stringRes("Zapp"),
                                subtitle = styledStringResource("t1abc...xyz"),
                                isShielded = false,
                                onCopyClicked = {},
                                onQrClicked = { },
                                onRequestClicked = { },
                                isExpanded = false,
                                onClick = {},
                                colorMode = ReceiveAddressState.ColorMode.DEFAULT,
                                infoIconButton =
                                    IconButtonState(
                                        R.drawable.ic_receive_zashi_shielded_info,
                                        onClick = {}
                                    ),
                                qrData = "t1abcxyz",
                                addressRaw = "t1abcxyz",
                            )
                        ),
                    isLoading = false,
                    onBack = {}
                ),
            appBarState = ZashiMainTopAppBarStateFixture.new()
        )
    }

@PreviewScreens
@Composable
private fun KeystonePreview() =
    ZcashTheme {
        ReceiveView(
            state =
                ReceiveState(
                    items =
                        listOf(
                            ReceiveAddressState(
                                icon = co.electriccoin.zcash.ui.design.R.drawable.ic_item_keystone,
                                title = stringRes("Zapp"),
                                subtitle = styledStringResource("u1sscfn3f3...83ruj7gfcd"),
                                isShielded = true,
                                onCopyClicked = {},
                                onQrClicked = { },
                                onRequestClicked = {},
                                isExpanded = true,
                                onClick = {},
                                colorMode = ReceiveAddressState.ColorMode.KEYSTONE,
                                infoIconButton =
                                    IconButtonState(
                                        R.drawable.ic_receive_zashi_shielded_info,
                                        onClick = {}
                                    ),
                                qrData = "u1sscfn3f3abcdef83ruj7gfcd",
                                addressRaw = "u1sscfn3f3abcdef83ruj7gfcd",
                            ),
                            ReceiveAddressState(
                                icon = co.electriccoin.zcash.ui.design.R.drawable.ic_item_keystone,
                                title = stringRes("Zapp"),
                                subtitle = styledStringResource("t1abc...xyz"),
                                isShielded = false,
                                onCopyClicked = {},
                                onQrClicked = { },
                                onRequestClicked = { },
                                isExpanded = false,
                                onClick = {},
                                colorMode = ReceiveAddressState.ColorMode.DEFAULT,
                                infoIconButton =
                                    IconButtonState(
                                        R.drawable.ic_receive_zashi_shielded_info,
                                        onClick = {}
                                    ),
                                qrData = "t1abcxyz",
                                addressRaw = "t1abcxyz",
                            )
                        ),
                    isLoading = false,
                    onBack = {}
                ),
            appBarState = ZashiMainTopAppBarStateFixture.new()
        )
    }
