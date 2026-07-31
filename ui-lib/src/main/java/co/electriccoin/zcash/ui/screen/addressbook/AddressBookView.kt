package co.electriccoin.zcash.ui.screen.addressbook

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.listitem.ContactListItemState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBookView(
    state: AddressBookState
) {
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    BlankBgScaffold(
        topBar = {
            ZappScreenHeader(
                title = state.title.getValue(),
                modifier =
                    Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .testTag(AddressBookTag.TOP_APP_BAR),
            )
        },
        bottomBar = {
            AddressBookBottomBar(
                onBack = state.onBack,
                onAddContact = { showAddSheet = true },
            )
        },
    ) { paddingValues ->
        when {
            state.items.isEmpty() && state.isLoading -> {
                CircularScreenProgressIndicator()
            }

            state.items.isEmpty() && !state.isLoading -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(id = R.string.address_book_empty),
                        style =
                            ZappTheme.typography.rowTitle.copy(
                                color = ZappTheme.colors.text,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                            ),
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                ) {
                    itemsIndexed(
                        contentType = { _, item -> item.contentType },
                        items = state.items,
                    ) { index, item ->
                        when (item) {
                            is AddressBookItem.Contact -> {
                                ZappContactRow(
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    state = item.state,
                                )
                                if (index != state.items.lastIndex &&
                                    state.items[index + 1] is AddressBookItem.Contact
                                ) {
                                    ZappRowDivider(
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        inset = true,
                                    )
                                }
                            }

                            is AddressBookItem.Title -> {
                                if (index == 0) {
                                    Spacer(Modifier.height(16.dp))
                                } else {
                                    Spacer(Modifier.height(20.dp))
                                }
                                ZappSectionTitle(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    state = item
                                )
                                Spacer(Modifier.height(4.dp))
                            }

                            AddressBookItem.Empty -> {
                                Spacer(modifier = Modifier.height(68.dp))
                                EmptyItem(
                                    modifier =
                                        Modifier
                                            .padding(horizontal = 20.dp)
                                            .fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Track which additional-address field is waiting for a scan result
    var scanTargetField by rememberSaveable { mutableStateOf<String?>(null) }

    // Add Contact bottom sheet
    if (showAddSheet) {
        AddContactSheet(
            scannedMessagingKey = state.scannedMessagingKey,
            onConsumeScannedMessagingKey = { state.onConsumeScannedMessagingKey?.invoke() },
            onScanMessagingKey = { state.onScanMessagingKey?.invoke() },
            scannedAddress = state.scannedAddress,
            onConsumeScannedAddress = { state.onConsumeScannedAddress?.invoke() },
            onScanWalletAddress = { state.onScanQr?.invoke() },
            scanTargetField = scanTargetField,
            onScanAddrField = { addrType ->
                scanTargetField = addrType
                state.onScanWalletAddress?.invoke() ?: state.onScanQr?.invoke()
            },
            onConsumeScanTarget = { scanTargetField = null },
            onDismiss = { showAddSheet = false },
            onAdd = { name, messagingKey, walletAddress, walletAddresses ->
                state.onSaveNewContact?.invoke(name, messagingKey, walletAddress, walletAddresses)
                showAddSheet = false
            },
        )
    }

    // Edit Contact bottom sheet
    state.editingContact?.let { editData ->
        EditContactSheet(
            editData = editData,
            scannedAddress = state.scannedAddress,
            onConsumeScannedAddress = { state.onConsumeScannedAddress?.invoke() },
            scanTargetField = scanTargetField,
            onScanAddrField = { addrType ->
                scanTargetField = addrType
                state.onScanWalletAddress?.invoke() ?: state.onScanQr?.invoke()
            },
            onConsumeScanTarget = { scanTargetField = null },
            onDismiss = { state.onDismissEdit?.invoke() },
            onSave = { name, walletAddress, walletAddresses ->
                state.onUpdateContact?.invoke(name, walletAddress, walletAddresses)
            },
            onDelete = {
                state.onDeleteContact?.invoke()
            },
        )
    }
}

// ── Bottom bar: back + Add New Contact on the same row ───────────────────────

@Composable
private fun AddressBookBottomBar(
    onBack: () -> Unit,
    onAddContact: () -> Unit,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp)
                .padding(bottom = 8.dp)
                .background(c.surface)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ZappBackButton(onClick = onBack)
        ZappButton(
            text = stringResource(R.string.address_book_add),
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            variant = ZappButtonVariant.Primary,
            onClick = onAddContact,
        )
    }
}

// ── List components ──────────────────────────────────────────────────────────

@Composable
private fun ZappSectionTitle(
    state: AddressBookItem.Title,
    modifier: Modifier = Modifier
) {
    BasicText(
        modifier = modifier,
        text = state.title.getValue().uppercase(),
        style =
            ZappTheme.typography.groupLabel.copy(
                color = ZappTheme.colors.textMuted,
                fontWeight = FontWeight.Black,
            ),
    )
}

@Composable
private fun EmptyItem(modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    Box(
        modifier =
            modifier
                .border(1.dp, c.border, RectangleShape)
                .padding(horizontal = 20.dp, vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = stringResource(id = R.string.address_book_empty),
            style =
                ZappTheme.typography.rowTitle.copy(
                    color = c.text,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}

@Composable
private fun ZappContactRow(
    state: ContactListItemState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = c.accent),
                    onClick = state.onClick,
                ).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ZappContactAvatar(state = state)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = state.name.getValue(),
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = state.address.getValue(),
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ZappContactAvatar(
    state: ContactListItemState,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    val icon = state.bigIcon
    val displayText = if (icon is ImageResource.DisplayString) icon.value else "?"
    val textColor = if (icon is ImageResource.DisplayString) c.text else c.textMuted
    Box(
        modifier =
            modifier
                .size(40.dp)
                .background(c.surfaceAlt, RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = displayText,
            style =
                ZappTheme.typography.rowTitle.copy(
                    color = textColor,
                    fontWeight = FontWeight.Black,
                ),
        )
    }
}

// ── Previews ─────────────────────────────────────────────────────────────────

@PreviewScreens
@Composable
private fun AddressBookDataPreview() {
    ZcashTheme {
        AddressBookView(
            state =
                AddressBookState(
                    isLoading = false,
                    onBack = {},
                    items =
                        listOf(
                            AddressBookItem.Title(stringRes("Title")),
                            AddressBookItem.Contact(
                                ContactListItemState(
                                    name = stringRes("Name Surname"),
                                    address = stringRes("3iY5ZSkRnevzSMu4hosasdasdasdasd12312312dasd9hw2").withStyle(),
                                    bigIcon = imageRes("NS"),
                                    smallIcon = null,
                                    isShielded = false,
                                    onClick = {}
                                )
                            ),
                        ),
                    scanButton = ButtonState(text = stringRes("Scan")),
                    manualButton = ButtonState(text = stringRes("Manual")),
                    title = stringRes("Address book"),
                    info = null,
                    onSaveNewContact = { _, _, _, _ -> },
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun EmptyAddressBookPreview() {
    ZcashTheme {
        AddressBookView(
            state =
                AddressBookState(
                    isLoading = false,
                    onBack = {},
                    items = emptyList(),
                    scanButton = ButtonState(text = stringRes("Scan")),
                    manualButton = ButtonState(text = stringRes("Manual")),
                    title = stringRes("Select Recipient"),
                    info = null,
                    onSaveNewContact = { _, _, _, _ -> },
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun LoadingPreview() {
    ZcashTheme {
        AddressBookView(
            state =
                AddressBookState(
                    isLoading = true,
                    onBack = {},
                    items = emptyList(),
                    scanButton = ButtonState(text = stringRes("Scan")),
                    manualButton = ButtonState(text = stringRes("Manual")),
                    title = stringRes("Select Recipient"),
                    info = null,
                ),
        )
    }
}
