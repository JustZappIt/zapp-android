package co.electriccoin.zcash.ui.screen.addressbook

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

// ── Edit Contact bottom sheet ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditContactSheet(
    editData: EditContactData,
    scannedAddress: String?,
    onConsumeScannedAddress: () -> Unit,
    scanTargetField: String?,
    onScanAddrField: (addrType: String) -> Unit,
    onConsumeScanTarget: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (name: String, walletAddress: String, walletAddresses: Map<String, String>) -> Unit,
    onDelete: () -> Unit,
) {
    val c = ZappTheme.colors
    var nameInput by remember { mutableStateOf(TextFieldValue(editData.originalName)) }
    var walletAddressInput by remember { mutableStateOf(TextFieldValue(editData.originalAddress)) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAdditionalAddresses by remember {
        mutableStateOf(editData.walletAddresses.isNotEmpty())
    }

    // Additional wallet address fields
    var transparentAddr by remember {
        mutableStateOf(TextFieldValue(editData.walletAddresses[AddressBookContact.ADDR_TYPE_TRANSPARENT].orEmpty()))
    }
    var evmAddr by remember {
        mutableStateOf(TextFieldValue(editData.walletAddresses[AddressBookContact.ADDR_TYPE_EVM].orEmpty()))
    }
    var solanaAddr by remember {
        mutableStateOf(TextFieldValue(editData.walletAddresses[AddressBookContact.ADDR_TYPE_SOLANA].orEmpty()))
    }

    // Route scanned address to the target field
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

    fun collectWalletAddresses(): Map<String, String> =
        buildMap {
            if (transparentAddr.text.isNotBlank()) put(AddressBookContact.ADDR_TYPE_TRANSPARENT, transparentAddr.text.trim())
            if (evmAddr.text.isNotBlank()) put(AddressBookContact.ADDR_TYPE_EVM, evmAddr.text.trim())
            if (solanaAddr.text.isNotBlank()) put(AddressBookContact.ADDR_TYPE_SOLANA, solanaAddr.text.trim())
        }

    val hasAddrChanges =
        transparentAddr.text.trim() != editData.walletAddresses[AddressBookContact.ADDR_TYPE_TRANSPARENT].orEmpty() ||
            evmAddr.text.trim() != editData.walletAddresses[AddressBookContact.ADDR_TYPE_EVM].orEmpty() ||
            solanaAddr.text.trim() != editData.walletAddresses[AddressBookContact.ADDR_TYPE_SOLANA].orEmpty()

    val hasChanges =
        nameInput.text.trim() != editData.originalName ||
            walletAddressInput.text.trim() != editData.originalAddress ||
            hasAddrChanges
    val isValid =
        nameInput.text.isNotBlank() &&
            (walletAddressInput.text.isNotBlank() || editData.originalAddress.isBlank())

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
                text = stringResource(R.string.address_book_edit_contact_title),
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

            // Primary Wallet Address field
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

            // Additional Addresses toggle
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

            // Delete confirmation inline
            if (showDeleteConfirm) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(c.dangerSoft, RectangleShape)
                            .border(BorderStroke(1.dp, c.danger), RectangleShape)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Column {
                        BasicText(
                            text = stringResource(R.string.address_book_edit_delete_confirm_title),
                            style =
                                ZappTheme.typography.rowTitle.copy(
                                    color = c.danger,
                                    fontWeight = FontWeight.Black,
                                ),
                        )
                        Spacer(Modifier.height(4.dp))
                        BasicText(
                            text = stringResource(R.string.address_book_edit_delete_confirm_message),
                            style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Cancel
                            val cancelDeleteDescription = stringResource(R.string.address_book_edit_cancel_delete_content_description)
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .border(BorderStroke(1.dp, c.border), RectangleShape)
                                        .clickable(onClick = { showDeleteConfirm = false })
                                        .semantics {
                                            contentDescription = cancelDeleteDescription
                                            role = Role.Button
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    text = stringResource(R.string.address_book_edit_delete_confirm_no).uppercase(),
                                    style =
                                        ZappTheme.typography.button.copy(
                                            color = c.text,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 0.6.sp,
                                        ),
                                )
                            }
                            // Confirm delete
                            val confirmDeleteDescription = stringResource(R.string.address_book_edit_confirm_delete_content_description)
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .background(c.danger, RectangleShape)
                                        .clickable(onClick = onDelete)
                                        .semantics {
                                            contentDescription = confirmDeleteDescription
                                            role = Role.Button
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    text = stringResource(R.string.address_book_edit_delete_confirm_yes).uppercase(),
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
                Spacer(Modifier.height(12.dp))
            }

            // SAVE CHANGES CTA
            if (!showDeleteConfirm) {
                val keyboard = LocalSoftwareKeyboardController.current
                val saveEnabled = hasChanges && isValid
                val saveChangesDescription = stringResource(R.string.address_book_edit_save_content_description)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(
                                if (saveEnabled) c.accent else c.surfaceAlt,
                                RectangleShape,
                            ).then(
                                if (saveEnabled) {
                                    Modifier.clickable(onClick = {
                                        val name = nameInput.text.trim()
                                        val wallet = walletAddressInput.text.trim()
                                        when {
                                            name.isEmpty() -> {
                                                error = stringRes(R.string.address_book_contact_name_required)
                                            }

                                            else -> {
                                                keyboard?.hide()
                                                onSave(name, wallet, collectWalletAddresses())
                                            }
                                        }
                                    })
                                } else {
                                    Modifier
                                }
                            ).semantics {
                                contentDescription = saveChangesDescription
                                role = Role.Button
                                if (!saveEnabled) disabled()
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(R.string.address_book_edit_save_btn).uppercase(),
                        style =
                            ZappTheme.typography.button.copy(
                                color = if (saveEnabled) c.onAccent else c.textSubtle,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.6.sp,
                            ),
                    )
                }

                Spacer(Modifier.height(10.dp))

                // DELETE CONTACT button
                val deleteContactDescription = stringResource(R.string.address_book_edit_delete_content_description)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(c.dangerSoft, RectangleShape)
                            .clickable(onClick = { showDeleteConfirm = true })
                            .semantics {
                                contentDescription = deleteContactDescription
                                role = Role.Button
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(R.string.address_book_edit_delete_btn).uppercase(),
                        style =
                            ZappTheme.typography.button.copy(
                                color = c.danger,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.6.sp,
                            ),
                    )
                }
            }
        }
    }
}
