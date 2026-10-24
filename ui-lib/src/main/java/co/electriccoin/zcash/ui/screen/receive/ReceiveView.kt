package co.electriccoin.zcash.ui.screen.receive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.appbar.ZashiMainTopAppBarState
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappAddressCopyRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.styledStringResource
import co.electriccoin.zcash.ui.fixture.ZashiMainTopAppBarStateFixture

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
            val shieldedLabel = stringResource(R.string.receive_tab_shielded)
            val transparentLabel = stringResource(R.string.receive_tab_transparent)

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
                        labels = items.map { if (it.isShielded) shieldedLabel else transparentLabel },
                        selectedIndex = selectedIndex,
                        onSelect = { idx -> items.getOrNull(idx)?.onClick?.invoke() },
                    )
                }

                ReceiveShareButton(
                    address = selectedItem.addressRaw,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .padding(bottom = 8.dp),
                )

                ReceiveBottomDock(
                    onBack = state.onBack,
                    action = stringResource(R.string.receive_request),
                    onAction = selectedItem.onRequestClicked,
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
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))

        ReceiveQrFrame(qrData = selectedItem.qrData, centerImage = selectedItem.icon)

        Spacer(Modifier.height(20.dp))

        ZappAddressCopyRow(
            address = selectedItem.subtitle,
            copyContentDescription = stringResource(R.string.receive_copy),
            onCopy = selectedItem.onCopyClicked,
        )

        Spacer(Modifier.height(20.dp))

        ReceiveTip(
            label = stringResource(R.string.receive_tip_label),
            text = stringResource(id = R.string.receive_prioritize_shielded),
        )

        Spacer(Modifier.height(16.dp))
    }
}

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
