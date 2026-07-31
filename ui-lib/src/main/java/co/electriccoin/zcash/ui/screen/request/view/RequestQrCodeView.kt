package co.electriccoin.zcash.ui.screen.request.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.model.WalletAddress
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.design.component.QrCodeDefaults
import co.electriccoin.zcash.ui.design.component.ZashiModalBottomSheet
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.request.model.RequestChatPickerState
import co.electriccoin.zcash.ui.screen.request.model.RequestState
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
internal fun RequestQrCodeView(
    state: RequestState.QrCode,
    modifier: Modifier = Modifier
) {
    val c = ZappTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        AmountPill(state = state)

        Spacer(Modifier.height(20.dp))

        Box(
            modifier =
                Modifier
                    .background(c.bg, RectangleShape)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .padding(12.dp),
        ) {
            ZashiQr(
                state =
                    state.toQrState(
                        contentDescription = stringRes(R.string.request_qr_code_content_description),
                        centerImage = state.icon,
                    ),
                modifier = Modifier.fillMaxWidth(0.92f),
            )
        }

        Spacer(Modifier.height(12.dp))

        val colors = QrCodeDefaults.colors()
        val sizePixels = with(LocalDensity.current) { DEFAULT_QR_CODE_SIZE.toPx() }.roundToInt()
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QrActionButton(
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.Download,
                label = stringResource(R.string.request_qr_save_btn),
                onClick = { state.onQrCodeShare(colors, sizePixels, state.request.qrCodeState.requestUri) },
            )
            QrActionButton(
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Filled.Send,
                label = stringResource(R.string.request_qr_send_in_chat_btn),
                onClick = state.onSendInChat,
            )
        }

        Spacer(Modifier.height(20.dp))

        AddressSection(state = state)

        Spacer(Modifier.height(20.dp))
    }

    state.chatPicker?.let { ChatContactPickerSheet(picker = it) }
}

@Composable
private fun QrActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .heightIn(min = 52.dp)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = c.text,
            modifier = Modifier.size(18.dp),
        )
        BasicText(
            text = label,
            style = ZappTheme.typography.button.copy(color = c.text, fontWeight = FontWeight.Black),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContactPickerSheet(picker: RequestChatPickerState) {
    val c = ZappTheme.colors
    ZashiModalBottomSheet(
        onDismissRequest = picker.onDismiss,
        containerColor = c.surface,
        scrimColor = c.overlay,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
        ) {
            BasicText(
                text = stringResource(R.string.request_qr_send_in_chat_title),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            )
            Spacer(Modifier.height(12.dp))
            when {
                picker.isSending -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }

                picker.contacts.isEmpty() -> {
                    BasicText(
                        text = stringResource(R.string.request_qr_send_in_chat_empty),
                        style = ZappTheme.typography.body.copy(color = c.textMuted),
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(items = picker.contacts, key = { it.publicKey }) { contact ->
                            ChatContactPickerRow(displayName = contact.displayName, onSelect = contact.onSelect)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatContactPickerRow(
    displayName: String,
    onSelect: () -> Unit,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            tint = c.accent,
            modifier = Modifier.size(20.dp),
        )
        BasicText(
            text = displayName,
            style = ZappTheme.typography.rowTitle.copy(color = c.text),
        )
    }
}

@Composable
private fun AmountPill(
    state: RequestState.QrCode,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val ticker = CURRENCY_TICKER
    Row(
        modifier =
            modifier
                .background(c.accentSoft, RectangleShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            text = state.request.qrCodeState.zecAmount,
            style =
                ZappTheme.typography.button.copy(
                    color = c.text,
                    fontWeight = FontWeight.Black,
                ),
        )
        BasicText(
            text = ticker,
            style =
                ZappTheme.typography.button.copy(
                    color = c.accent,
                    fontWeight = FontWeight.Black,
                ),
        )
    }
}

@Composable
private fun AddressSection(
    state: RequestState.QrCode,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val copyToClipboard = koinInject<CopyToClipboardUseCase>()
    val isShielded = state.walletAddress !is WalletAddress.Transparent
    val addressLabel =
        if (isShielded) {
            stringResource(R.string.request_qr_address_label_shielded)
        } else {
            stringResource(R.string.request_qr_address_label_transparent)
        }
    val address = state.walletAddress.address
    val truncated =
        if (address.length > 16) {
            address.take(10) + "…" + address.takeLast(6)
        } else {
            address
        }

    Column(modifier = modifier.fillMaxWidth()) {
        BasicText(
            text = addressLabel.uppercase(),
            style =
                ZappTheme.typography.eyebrow.copy(
                    color = c.textSubtle,
                    fontSize = 10.sp,
                    letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.Black,
                ),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = truncated,
                style =
                    ZappTheme.typography.mono.copy(
                        color = c.textMuted,
                        fontSize = 12.sp,
                    ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .border(BorderStroke(1.dp, c.border), RectangleShape)
                        .clickable { copyToClipboard(address) }
                        .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_copy_shielded),
                    contentDescription = stringResource(R.string.request_qr_copy_address_content_description),
                    modifier = Modifier.size(18.dp),
                    colorFilter = ColorFilter.tint(c.accentText),
                )
            }
        }

        if (state.request.qrCodeState.memo
                .isNotBlank()
        ) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(BorderStroke(1.dp, c.border), RectangleShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column {
                    BasicText(
                        text = stringResource(R.string.request_qr_note_label),
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
                        text = state.request.qrCodeState.memo,
                        style =
                            ZappTheme.typography.body.copy(
                                color = c.textMuted,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                            ),
                    )
                }
            }
        }
    }
}
