package co.electriccoin.zcash.ui.screen.addressbook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

// ── Add Contact bottom sheet ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddContactSheet(
    scannedMessagingKey: String?,
    onConsumeScannedMessagingKey: () -> Unit,
    onScanMessagingKey: () -> Unit,
    scannedAddress: String?,
    onConsumeScannedAddress: () -> Unit,
    onScanWalletAddress: () -> Unit,
    scanTargetField: String?,
    onScanAddrField: (addrType: String) -> Unit,
    onConsumeScanTarget: () -> Unit,
    onDismiss: () -> Unit,
    onAdd: (name: String, messagingKey: String, walletAddress: String, walletAddresses: Map<String, String>) -> Unit,
) {
    val c = ZappTheme.colors
    var nameInput by remember { mutableStateOf(TextFieldValue("")) }
    var messagingKeyInput by remember { mutableStateOf(TextFieldValue("")) }
    var walletAddressInput by remember { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var showAdditionalAddresses by remember { mutableStateOf(false) }
    var transparentAddr by remember { mutableStateOf(TextFieldValue("")) }
    var evmAddr by remember { mutableStateOf(TextFieldValue("")) }
    var solanaAddr by remember { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(scannedMessagingKey) {
        scannedMessagingKey?.let { key ->
            messagingKeyInput = TextFieldValue(key)
            error = null
            onConsumeScannedMessagingKey()
        }
    }

    // Route scanned address to the target additional-address field
    LaunchedEffect(scannedAddress, scanTargetField) {
        if (scannedAddress != null && scanTargetField != null) {
            val tfv = TextFieldValue(scannedAddress)
            when (scanTargetField) {
                AddressBookContact.ADDR_TYPE_TRANSPARENT -> transparentAddr = tfv
                AddressBookContact.ADDR_TYPE_EVM -> evmAddr = tfv
                AddressBookContact.ADDR_TYPE_SOLANA -> solanaAddr = tfv
            }
            showAdditionalAddresses = true
            onConsumeScannedAddress()
            onConsumeScanTarget()
        }
    }

    LaunchedEffect(scannedAddress) {
        scannedAddress?.let { addr ->
            if (scanTargetField == null) {
                walletAddressInput = TextFieldValue(addr)
                error = null
                onConsumeScannedAddress()
            }
        }
    }

    val cleanedKey by remember { derivedStateOf { messagingKeyInput.text.trim().removePrefix("0x") } }
    val isValidKey by remember {
        derivedStateOf {
            cleanedKey.length == 64 &&
                cleanedKey.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RectangleShape,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp),
        ) {
            BasicText(
                text = stringResource(R.string.add_new_contact_title),
                style =
                    ZappTheme.typography.screenTitle.copy(
                        color = c.text,
                        fontWeight = FontWeight.Black,
                    ),
            )

            Spacer(Modifier.height(20.dp))

            // Name field
            ZappInputField(
                value = nameInput,
                onValueChange = {
                    nameInput = it
                    error = null
                },
                placeholder = stringResource(R.string.contact_name_hint),
                leadingIcon = {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = c.textSubtle,
                    )
                },
            )

            Spacer(Modifier.height(12.dp))

            // Messaging Key field
            ZappInputField(
                value = messagingKeyInput,
                onValueChange = {
                    messagingKeyInput = it
                    error = null
                },
                placeholder = stringResource(R.string.address_book_messaging_key_hint),
                leadingIcon = {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = c.textSubtle,
                    )
                },
                trailingIcon = {
                    val scanMessagingKeyDescription = stringResource(R.string.address_book_scan_messaging_key_content_description)
                    Box(
                        modifier =
                            Modifier
                                .size(48.dp)
                                .clickable(onClick = onScanMessagingKey)
                                .semantics {
                                    contentDescription = scanMessagingKeyDescription
                                    role = Role.Button
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = c.textSubtle,
                        )
                    }
                },
            )

            // Valid key confirmation row
            if (isValidKey) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(c.successSoft, RectangleShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = c.success,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        text = "${cleanedKey.take(10)}…${cleanedKey.takeLast(6)}",
                        style = ZappTheme.typography.chip.copy(color = c.success),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Wallet Address field
            ZappInputField(
                value = walletAddressInput,
                onValueChange = {
                    walletAddressInput = it
                    error = null
                },
                placeholder = stringResource(R.string.contact_address_hint),
                leadingIcon = {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = c.textSubtle,
                    )
                },
                trailingIcon = {
                    val scanWalletAddressDescription = stringResource(R.string.address_book_scan_wallet_address_content_description)
                    Box(
                        modifier =
                            Modifier
                                .size(48.dp)
                                .clickable(onClick = onScanWalletAddress)
                                .semantics {
                                    contentDescription = scanWalletAddressDescription
                                    role = Role.Button
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = c.textSubtle,
                        )
                    }
                },
            )

            // Inline error
            error?.let {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.caption.copy(color = c.danger),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Additional Addresses section
            WalletAddressesSection(
                expanded = showAdditionalAddresses,
                onToggle = { showAdditionalAddresses = !showAdditionalAddresses },
                transparentAddr = transparentAddr,
                onTransparentChange = { transparentAddr = it },
                evmAddr = evmAddr,
                onEvmChange = { evmAddr = it },
                solanaAddr = solanaAddr,
                onSolanaChange = { solanaAddr = it },
                onScanAddress = onScanAddrField,
            )

            Spacer(Modifier.height(20.dp))

            // ADD CONTACT CTA
            val keyboard = LocalSoftwareKeyboardController.current
            val addContactDescription = stringResource(R.string.address_book_add_contact_content_description)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(c.accent, RectangleShape)
                        .clickable(onClick = {
                            val name = nameInput.text.trim()
                            val mk = messagingKeyInput.text.trim().removePrefix("0x")
                            val wallet = walletAddressInput.text.trim()
                            val addrs =
                                buildMap {
                                    if (transparentAddr.text.isNotBlank()) {
                                        put(
                                            AddressBookContact.ADDR_TYPE_TRANSPARENT,
                                            transparentAddr.text.trim()
                                        )
                                    }
                                    if (evmAddr.text.isNotBlank()) put(AddressBookContact.ADDR_TYPE_EVM, evmAddr.text.trim())
                                    if (solanaAddr.text.isNotBlank()) put(AddressBookContact.ADDR_TYPE_SOLANA, solanaAddr.text.trim())
                                }
                            when {
                                name.isEmpty() -> {
                                    error = stringRes(R.string.address_book_contact_name_required)
                                }

                                else -> {
                                    keyboard?.hide()
                                    onAdd(name, mk, wallet, addrs)
                                }
                            }
                        })
                        .semantics {
                            contentDescription = addContactDescription
                            role = Role.Button
                        },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = stringResource(R.string.add_new_contact_primary_btn).uppercase(),
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
}
