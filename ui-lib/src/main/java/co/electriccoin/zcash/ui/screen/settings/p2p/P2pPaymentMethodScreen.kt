package co.electriccoin.zcash.ui.screen.settings.p2p

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.P2pProvider
import co.electriccoin.zcash.ui.common.model.P2pRail
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSettingsGroup
import co.electriccoin.zcash.ui.design.component.zapp.ZappStatusChip
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.upi.toOfframpCorridorUi
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.peer.PeerPlatform

@Composable
internal fun P2pPaymentMethodScreen() {
    val vm = koinViewModel<P2pPaymentMethodVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    P2pPaymentMethodView(
        state = state,
    )
}

@Composable
private fun P2pPaymentMethodView(
    state: P2pPaymentMethodState,
) {
    val c = ZappTheme.colors
    var showInfo by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = {
            ZappScreenHeader(
                title = stringResource(R.string.settings_p2p_payment_method_title),
                right = {
                    IconButton(onClick = { showInfo = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
                            contentDescription =
                                stringResource(R.string.settings_p2p_payment_method_info_content_description),
                            tint = c.text,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        },
        bottomBar = {
            ZappBottomActionBar(
                onBack = state.onBack,
                primaryAction = {
                    ZappButton(
                        text = state.saveButton.text.getValue(),
                        onClick = state.saveButton.onClick,
                        enabled = state.saveButton.isEnabled,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                    )
                },
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = it.calculateTopPadding() + 12.dp,
                        bottom = it.calculateBottomPadding() + 12.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.sections.forEach { section ->
                ZappSettingsGroup(
                    title = section.provider.title(),
                    titleLogo = section.provider.logo(),
                ) {
                    section.items.forEachIndexed { index, item ->
                        P2pPaymentMethodRow(item)
                        if (index != section.items.lastIndex) {
                            ZappRowDivider()
                        }
                    }
                }
            }
        }
    }

    if (showInfo) {
        P2pHowItWorksSheet(state = state, onDismiss = { showInfo = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun P2pHowItWorksSheet(state: P2pPaymentMethodState, onDismiss: () -> Unit) {
    val c = ZappTheme.colors
    ZashiScreenModalBottomSheet(onDismissRequest = onDismiss) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                text = stringResource(R.string.settings_p2p_payment_method_info_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            BasicText(
                text = stringResource(R.string.settings_p2p_payment_method_info_rails),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            BasicText(
                text = stringResource(R.string.settings_p2p_payment_method_info_cash_out),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            BasicText(
                text = stringResource(R.string.settings_p2p_payment_method_info_flow),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            state.baseAddress?.let { address ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(c.surfaceAlt, RectangleShape)
                            .border(BorderStroke(1.dp, c.border), RectangleShape)
                            .padding(16.dp),
                ) {
                    BasicText(
                        text = stringResource(R.string.settings_p2p_payment_method_info_base_label),
                        style = ZappTheme.typography.caption.copy(color = c.textMuted),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            text = address,
                            style = ZappTheme.typography.mono.copy(color = c.text),
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                        Spacer(Modifier.width(8.dp))
                        CopyIconButton(
                            showCopiedFeedback = state.isAddressCopied,
                            onClick = state.onCopyBaseAddress,
                        )
                    }
                }
            }
            ZappButton(
                text = stringResource(co.electriccoin.zcash.ui.design.R.string.general_ok),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun CopyIconButton(showCopiedFeedback: Boolean, onClick: () -> Unit) {
    val c = ZappTheme.colors
    val label =
        if (showCopiedFeedback) {
            stringResource(R.string.settings_p2p_payment_method_info_copied_content_description)
        } else {
            stringResource(R.string.settings_p2p_payment_method_info_copy_content_description)
        }
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (showCopiedFeedback) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = null,
            tint = if (showCopiedFeedback) c.success else c.textMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun P2pPaymentMethodRow(item: P2pPaymentMethodItemState) {
    val c = ZappTheme.colors
    val title = item.rail.title()
    val subtitle = item.rail.subtitle()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = item.isAvailable,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = c.accent),
                    onClick = item.onClick,
                ).semantics {
                    role = Role.Button
                    contentDescription = title
                }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RailTile(item.rail)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
            )
            BasicText(
                text = subtitle,
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
            )
        }
        when {
            !item.isAvailable -> {
                Spacer(Modifier.width(8.dp))
                ZappStatusChip(text = stringResource(R.string.settings_p2p_payment_method_coming_soon))
            }

            item.isSelected -> {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = c.accentText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun RailTile(rail: P2pRail) {
    val painter =
        when (rail) {
            is P2pRail.ScanAndPay -> painterResource(rail.currency.toOfframpCorridorUi().flag)
            is P2pRail.PeerCashOut -> painterResource(rail.platform.logo())
        }
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(width = TILE_WIDTH.dp, height = TILE_HEIGHT.dp),
    )
}

private const val TILE_WIDTH = 30
private const val TILE_HEIGHT = 20

@Serializable
data object P2pPaymentMethodArgs

@PreviewScreens
@Composable
private fun P2pPaymentMethodPreview() =
    ZcashTheme {
        P2pPaymentMethodView(
            state =
                P2pPaymentMethodState(
                    baseAddress = "0x9858Effd232B4033e47d90003d41Ec34ECAEDA94",
                    isAddressCopied = false,
                    sections =
                        listOf(
                            P2pPaymentMethodSectionState(
                                provider = P2pProvider.P2P_ME,
                                items =
                                    P2pPaymentMethod.entries.map {
                                        P2pPaymentMethodItemState(
                                            rail = P2pRail.ScanAndPay(it.currency),
                                            isSelected = it.currency == CurrencyCode.Inr,
                                            isAvailable = it.available,
                                            onClick = {},
                                        )
                                    },
                            ),
                            P2pPaymentMethodSectionState(
                                provider = P2pProvider.PEER,
                                items =
                                    PeerPlatform.entries.map {
                                        P2pPaymentMethodItemState(
                                            rail = P2pRail.PeerCashOut(it),
                                            isSelected = false,
                                            isAvailable = true,
                                            onClick = {},
                                        )
                                    },
                            ),
                        ),
                    saveButton =
                        ButtonState(
                            text = stringRes(R.string.settings_p2p_payment_method_save),
                            onClick = {},
                        ),
                    onCopyBaseAddress = {},
                    onBack = {},
                ),
        )
    }
