@file:Suppress("TooManyFunctions")

package co.electriccoin.zcash.ui.screen.qrcode

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.fixture.WalletAddressFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappChipVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappEyebrowTopAppBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappStackedActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappStatusChip
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.Ellipsize
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.design.util.styleAsAddress
import kotlinx.coroutines.runBlocking

@Composable
@PreviewScreens
private fun QrCodeLoadingPreview() =
    ZcashTheme(forceDarkMode = true) {
        QrCodeView(
            state = QrCodeState.Loading,
            snackbarHostState = SnackbarHostState(),
        )
    }

@Composable
@PreviewScreens
private fun ZashiPreview() =
    ZcashTheme(forceDarkMode = false) {
        val address = runBlocking { WalletAddressFixture.unified() }
        QrCodeView(
            state =
                QrCodeState.Prepared(
                    qrCodeType = QrCodeType.ZASHI,
                    walletAddress = address,
                    formatterAddress = stringResByAddress(address.address, Ellipsize.MIDDLE),
                    onAddressCopy = {},
                    onQrCodeShare = {},
                    onBack = {},
                ),
            snackbarHostState = SnackbarHostState(),
        )
    }

@Composable
@PreviewScreens
private fun KeystonePreview() =
    ZcashTheme(forceDarkMode = false) {
        val address = runBlocking { WalletAddressFixture.unified() }
        QrCodeView(
            state =
                QrCodeState.Prepared(
                    qrCodeType = QrCodeType.KEYSTONE,
                    walletAddress = address,
                    formatterAddress = stringResByAddress(address.address, Ellipsize.MIDDLE),
                    onAddressCopy = {},
                    onQrCodeShare = {},
                    onBack = {},
                ),
            snackbarHostState = SnackbarHostState(),
        )
    }

@Composable
internal fun QrCodeView(
    state: QrCodeState,
    snackbarHostState: SnackbarHostState,
) {
    when (state) {
        QrCodeState.Loading -> {
            CircularScreenProgressIndicator()
        }

        is QrCodeState.Prepared -> {
            ProvideZappTheme {
                QrCodePrepared(state = state, snackbarHostState = snackbarHostState)
            }
        }
    }
}

@Composable
private fun QrCodePrepared(
    state: QrCodeState.Prepared,
    snackbarHostState: SnackbarHostState,
) {
    BlankBgScaffold(
        topBar = {
            ZappEyebrowTopAppBar(
                eyebrow = stringResource(id = R.string.qr_code_receive_title),
                onClose = state.onBack,
                closeContentDescription = stringResource(id = R.string.qr_code_close_content_description),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { QrCodeBottomBar(state = state) },
    ) { paddingValues ->
        QrCodeContents(
            state = state,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        top = paddingValues.calculateTopPadding(),
                        bottom = paddingValues.calculateBottomPadding(),
                    ),
        )
    }
}

@Composable
private fun QrCodeBottomBar(state: QrCodeState.Prepared) {
    ZappStackedActionBar {
        ZappButton(
            text = stringResource(id = R.string.qr_code_share_btn).uppercase(),
            variant = ZappButtonVariant.Primary,
            onClick = { state.onQrCodeShare(state.walletAddress.address) },
            modifier = Modifier.fillMaxWidth(),
        )
        ZappButton(
            text = stringResource(id = R.string.qr_code_copy_btn).uppercase(),
            variant = ZappButtonVariant.Secondary,
            onClick = { state.onAddressCopy(state.walletAddress.address) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QrCodeContents(
    state: QrCodeState.Prepared,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        horizontalAlignment = CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))

        Box(
            modifier =
                Modifier
                    .background(c.bg, RectangleShape)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .padding(12.dp),
        ) {
            QrCode(state = state)
        }

        Spacer(Modifier.height(20.dp))

        AddressTypeChip(state = state)

        Spacer(Modifier.height(12.dp))

        BasicText(
            text = stringResource(id = addressLabelRes(state)),
            style =
                ZappTheme.typography.display.copy(
                    color = c.text,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.4).sp,
                    textAlign = TextAlign.Center,
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        ExpandableAddressRow(state = state)

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AddressTypeChip(state: QrCodeState.Prepared) {
    val c = ZappTheme.colors
    val isShielded =
        state.walletAddress is WalletAddress.Unified ||
            state.walletAddress is WalletAddress.Sapling
    if (isShielded) {
        ZappStatusChip(
            text = stringResource(id = R.string.qr_code_privacy_level_shielded).uppercase(),
            variant = ZappChipVariant.Success,
            dotColor = c.success,
        )
    } else {
        ZappStatusChip(
            text = stringResource(id = R.string.qr_code_privacy_level_transparent).uppercase(),
            variant = ZappChipVariant.Accent,
            dotColor = c.accent,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpandableAddressRow(state: QrCodeState.Prepared) {
    val c = ZappTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val text =
        if (expanded) {
            StringResource.ByString(state.walletAddress.address).styleAsAddress()
        } else {
            state.formatterAddress
        }
    BasicText(
        text = text.getValue(),
        style =
            ZappTheme.typography.body.copy(
                color = c.textMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .combinedClickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { expanded = !expanded },
                    onLongClick = { state.onAddressCopy(state.walletAddress.address) },
                ),
    )
}

@Composable
private fun QrCode(state: QrCodeState.Prepared) {
    val addressType = state.walletAddress.toAddressType()
    ZashiQr(
        state =
            state.toQrState(
                contentDescription = stringRes(addressType.qrContentDescription),
                centerImageResId = addressType.qrCenterImage,
            ),
    )
}

private fun addressLabelRes(state: QrCodeState.Prepared): Int =
    when (state.walletAddress) {
        is WalletAddress.Unified -> {
            when (state.qrCodeType) {
                QrCodeType.ZASHI -> R.string.qr_code_wallet_address_shielded
                QrCodeType.KEYSTONE -> R.string.qr_code_wallet_address_shielded_keystone
            }
        }

        is WalletAddress.Sapling -> {
            when (state.qrCodeType) {
                QrCodeType.ZASHI -> R.string.qr_code_wallet_address_sapling
                QrCodeType.KEYSTONE -> R.string.qr_code_wallet_address_sapling_keystone
            }
        }

        is WalletAddress.Transparent -> {
            R.string.qr_code_wallet_address_transparent
        }

        else -> {
            error("Unsupported address type: ${state.walletAddress}")
        }
    }

private enum class AddressType {
    UNIFIED,
    SAPLING,
    TRANSPARENT;

    val qrContentDescription: Int
        get() =
            when (this) {
                UNIFIED -> R.string.qr_code_unified_content_description
                SAPLING -> R.string.qr_code_sapling_content_description
                TRANSPARENT -> R.string.qr_code_transparent_content_description
            }

    val qrCenterImage: Int
        get() =
            when (this) {
                UNIFIED, SAPLING -> R.drawable.ic_zec_qr_shielded
                TRANSPARENT -> R.drawable.ic_zec_qr_transparent
            }
}

private fun WalletAddress.toAddressType() =
    when (this) {
        is WalletAddress.Unified -> AddressType.UNIFIED
        is WalletAddress.Sapling -> AddressType.SAPLING
        is WalletAddress.Transparent -> AddressType.TRANSPARENT
        else -> error("Unsupported address type: $this")
    }
