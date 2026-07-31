package co.electriccoin.zcash.ui.screen.addressbook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
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

// ── Additional Wallet Addresses section ──────────────────────────────────────

@Composable
internal fun WalletAddressesSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    transparentAddr: TextFieldValue,
    onTransparentChange: (TextFieldValue) -> Unit,
    evmAddr: TextFieldValue,
    onEvmChange: (TextFieldValue) -> Unit,
    solanaAddr: TextFieldValue,
    onSolanaChange: (TextFieldValue) -> Unit,
    onScanAddress: ((addrType: String) -> Unit)? = null,
) {
    val c = ZappTheme.colors
    val additionalAddressesLabel = stringResource(R.string.address_book_additional_addresses)

    // Toggle header
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .semantics {
                    contentDescription = additionalAddressesLabel
                    role = Role.Button
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 3dp accent stripe
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(c.accent, RectangleShape),
        )
        Spacer(Modifier.width(12.dp))
        BasicText(
            text = additionalAddressesLabel.uppercase(),
            style =
                ZappTheme.typography.eyebrow.copy(
                    color = c.accent,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                ),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = if (expanded) "−" else "+",
            style =
                ZappTheme.typography.button.copy(
                    color = c.accent,
                    fontWeight = FontWeight.Black,
                ),
        )
    }

    if (expanded) {
        Spacer(Modifier.height(12.dp))

        WalletAddressField(
            label = stringResource(R.string.address_book_addr_transparent),
            placeholder = stringResource(R.string.address_book_addr_transparent_hint),
            value = transparentAddr,
            onValueChange = onTransparentChange,
            onScan = onScanAddress?.let { { it(AddressBookContact.ADDR_TYPE_TRANSPARENT) } },
            validation = validateTransparentAddress(transparentAddr.text),
        )

        Spacer(Modifier.height(10.dp))

        WalletAddressField(
            label = stringResource(R.string.address_book_addr_evm),
            placeholder = stringResource(R.string.address_book_addr_evm_hint),
            value = evmAddr,
            onValueChange = onEvmChange,
            onScan = onScanAddress?.let { { it(AddressBookContact.ADDR_TYPE_EVM) } },
            validation = validateEvmAddress(evmAddr.text),
        )

        Spacer(Modifier.height(10.dp))

        WalletAddressField(
            label = stringResource(R.string.address_book_addr_solana),
            placeholder = stringResource(R.string.address_book_addr_solana_hint),
            value = solanaAddr,
            onValueChange = onSolanaChange,
            onScan = onScanAddress?.let { { it(AddressBookContact.ADDR_TYPE_SOLANA) } },
            validation = validateSolanaAddress(solanaAddr.text),
        )
    }
}

/** Validation result for a wallet address field. */
internal enum class AddrValidation { EMPTY, VALID, INVALID }

internal fun validateUnifiedAddress(addr: String): AddrValidation {
    if (addr.isBlank()) return AddrValidation.EMPTY
    val t = addr.trim()
    return if (t.startsWith("u1") && t.length >= 78) AddrValidation.VALID else AddrValidation.INVALID
}

internal fun validateTransparentAddress(addr: String): AddrValidation {
    if (addr.isBlank()) return AddrValidation.EMPTY
    val t = addr.trim()
    return if ((t.startsWith("t1") || t.startsWith("t3")) && t.length in 34..36) AddrValidation.VALID else AddrValidation.INVALID
}

internal fun validateEvmAddress(addr: String): AddrValidation {
    if (addr.isBlank()) return AddrValidation.EMPTY
    val t = addr.trim()
    return if (t.startsWith("0x") && t.length == 42 && t.drop(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        AddrValidation.VALID
    } else {
        AddrValidation.INVALID
    }
}

internal fun validateSolanaAddress(addr: String): AddrValidation {
    if (addr.isBlank()) return AddrValidation.EMPTY
    val t = addr.trim()
    return if (t.length in 32..44 && t.all { it.isLetterOrDigit() }) AddrValidation.VALID else AddrValidation.INVALID
}

@Composable
internal fun WalletAddressField(
    label: String,
    placeholder: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onScan: (() -> Unit)? = null,
    validation: AddrValidation = AddrValidation.EMPTY,
) {
    val c = ZappTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        BasicText(
            text = label.uppercase(),
            style =
                ZappTheme.typography.mono.copy(
                    color = c.textMuted,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                ),
        )
        Spacer(Modifier.height(4.dp))
        ZappInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            leadingIcon = {
                Icon(
                    Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint =
                        when (validation) {
                            AddrValidation.VALID -> c.success
                            AddrValidation.INVALID -> c.danger
                            AddrValidation.EMPTY -> c.textSubtle
                        },
                )
            },
            trailingIcon =
                if (onScan != null) {
                    {
                        val scanDescription = stringResource(R.string.address_book_scan_addr_qr_content_description, label)
                        Box(
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .clickable(onClick = onScan)
                                    .semantics {
                                        contentDescription = scanDescription
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
                    }
                } else {
                    null
                },
        )
        // Validation feedback
        if (validation == AddrValidation.INVALID) {
            Spacer(Modifier.height(3.dp))
            BasicText(
                text = stringResource(R.string.contact_address_error_invalid),
                style = ZappTheme.typography.caption.copy(color = c.danger),
            )
        } else if (validation == AddrValidation.VALID) {
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = c.success,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                BasicText(
                    text = "${value.text.trim().take(8)}…${value.text.trim().takeLast(6)}",
                    style = ZappTheme.typography.chip.copy(color = c.success),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
