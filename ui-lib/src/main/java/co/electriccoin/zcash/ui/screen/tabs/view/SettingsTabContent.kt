package co.electriccoin.zcash.ui.screen.tabs.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZappNavBar
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByFiatDisplayName
import co.electriccoin.zcash.ui.screen.chat.common.ChatBootstrap
import co.electriccoin.zcash.ui.screen.settings.p2p.P2pPaymentMethod
import co.electriccoin.zcash.ui.screen.settings.p2p.selectedSubtitle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import xyz.justzappit.offramp.p2p.CurrencyCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTabContent(
    onChatProfileClick: () -> Unit,
    onContactsClick: () -> Unit,
    onAppLockClick: () -> Unit,
    localCurrency: FiatCurrency,
    onLocalCurrencyClick: () -> Unit,
    p2pPaymentMethod: CurrencyCode,
    onChooseServerClick: () -> Unit,
    onTorClick: () -> Unit,
    onBackgroundDeliveryClick: () -> Unit,
    onReadReceiptsClick: () -> Unit,
    onOnlineStatusClick: () -> Unit,
    onP2pPaymentMethodClick: () -> Unit,
    walletViewModel: WalletViewModel = koinViewModel(),
) {
    val scope = rememberCoroutineScope()
    val c = ZappTheme.colors
    val bootstrap: ChatBootstrap = koinInject()
    val identity by bootstrap.identity.collectAsState()
    val secretState by walletViewModel.secretState.collectAsStateWithLifecycle()
    val hasWallet = secretState == SecretState.READY
    val snackbarHostState = remember { SnackbarHostState() }
    val localCurrencyName = stringResByFiatDisplayName(localCurrency).getValue()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = c.bg,
    ) { _ ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            ZappScreenHeader(title = stringResource(R.string.settings_you_title))

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = ZappNavBar.CLEARANCE_DP.dp),
            ) {
                identity?.let { id ->
                    ProfileCard(
                        displayName = id.displayName,
                        publicKey = id.publicKey,
                    )
                }

                SettingsGroup(title = stringResource(R.string.settings_group_people)) {
                    ZappRow(
                        title = stringResource(R.string.chat_contacts_title),
                        subtitle = stringResource(R.string.settings_contacts_subtitle),
                        icon = Icons.Default.Contacts,
                        iconTint = c.accentText,
                        iconBackground = c.accentSoft,
                        onClick = onContactsClick,
                    )
                }

                SettingsGroup(title = stringResource(R.string.settings_group_security)) {
                    ZappRow(
                        title = stringResource(R.string.settings_profile_identity_title),
                        subtitle = stringResource(R.string.settings_profile_identity_subtitle),
                        icon = Icons.Default.Person,
                        iconTint = c.accentText,
                        iconBackground = c.accentSoft,
                        onClick = onChatProfileClick,
                    )
                    ZappRowDivider(inset = true)
                    ZappRow(
                        title = stringResource(R.string.settings_app_lock_title),
                        subtitle = stringResource(R.string.settings_app_lock_subtitle),
                        icon = Icons.Default.Lock,
                        iconTint = c.accentText,
                        iconBackground = c.accentSoft,
                        onClick = onAppLockClick,
                    )
                    // DEAD CODE [hidden]: Backup / restore — uncomment to restore (and the divider above)
                    // ZappRowDivider(inset = true)
                    // ZappRow(
                    //     title = "Backup / restore",
                    //     subtitle = "Coming soon",
                    //     icon = Icons.Default.Backup,
                    //     onClick = {
                    //         scope.launch { snackbarHostState.showSnackbar("Backup & restore coming soon.") }
                    //     },
                    // )
                }

                SettingsGroup(title = stringResource(R.string.settings_group_privacy)) {
                    ZappRow(
                        title = stringResource(R.string.settings_tor_title),
                        subtitle = stringResource(R.string.settings_tor_subtitle),
                        icon = Icons.Default.Security,
                        iconTint = c.accentText,
                        iconBackground = c.accentSoft,
                        onClick = onTorClick,
                    )
                    ZappRowDivider(inset = true)
                    ZappRow(
                        title = stringResource(R.string.chat_settings_background_push_toggle_title),
                        subtitle = stringResource(R.string.chat_settings_background_push_toggle_subtitle),
                        icon = Icons.Default.Notifications,
                        iconTint = c.accentText,
                        iconBackground = c.accentSoft,
                        onClick = onBackgroundDeliveryClick,
                    )
                    ZappRowDivider(inset = true)
                    ZappRow(
                        title = stringResource(R.string.chat_settings_read_receipts_toggle_title),
                        subtitle = stringResource(R.string.read_receipts_settings_row_subtitle),
                        icon = Icons.Default.Check,
                        iconTint = c.accentText,
                        iconBackground = c.accentSoft,
                        onClick = onReadReceiptsClick,
                    )
                    ZappRowDivider(inset = true)
                    ZappRow(
                        title = stringResource(R.string.chat_settings_online_status_toggle_title),
                        subtitle = stringResource(R.string.online_status_settings_row_subtitle),
                        icon = Icons.Default.Person,
                        iconTint = c.accentText,
                        iconBackground = c.accentSoft,
                        onClick = onOnlineStatusClick,
                    )
                }

                SettingsGroup(title = stringResource(R.string.settings_group_p2p)) {
                    ZappRow(
                        title = stringResource(R.string.settings_p2p_payment_method_title),
                        subtitle = P2pPaymentMethod.fromCurrency(p2pPaymentMethod).selectedSubtitle(),
                        icon = Icons.Default.Payment,
                        iconTint = c.accentText,
                        iconBackground = c.accentSoft,
                        onClick = onP2pPaymentMethodClick,
                    )
                }

                if (hasWallet) {
                    SettingsGroup(title = stringResource(R.string.settings_group_wallet)) {
                        // DEAD CODE [hidden]: Backup seed phrase — uncomment to restore (and the divider below)
                        // ZappRow(
                        //     title = "Backup seed phrase",
                        //     subtitle = "View and save your 24-word recovery phrase",
                        //     icon = Icons.Default.AccountBalanceWallet,
                        //     iconTint = c.accentText,
                        //     iconBackground = c.accentSoft,
                        //     onClick = { /* route via TabsVM */ },
                        // )
                        // ZappRowDivider(inset = true)
                        // Without a CMC key only USD resolves (non-USD falls back to the USD-only
                        // rate), so gate the row like ExchangeRateSettingsVM / ExchangeRateOptInView.
                        if (VersionInfo.IS_CMC_AVAILABLE) {
                            ZappRow(
                                title = stringResource(R.string.settings_local_currency_title),
                                subtitle =
                                    stringResource(
                                        R.string.settings_local_currency_subtitle,
                                        localCurrency.code,
                                        localCurrencyName,
                                    ),
                                icon = Icons.Default.Payment,
                                iconTint = c.accentText,
                                iconBackground = c.accentSoft,
                                onClick = onLocalCurrencyClick,
                            )
                            ZappRowDivider(inset = true)
                        }
                        ZappRow(
                            title = stringResource(R.string.choose_server_title),
                            subtitle = stringResource(R.string.settings_server_subtitle),
                            icon = Icons.Default.Cloud,
                            iconTint = c.accentText,
                            iconBackground = c.accentSoft,
                            onClick = onChooseServerClick,
                        )
                    }
                }

                // DEAD CODE [hidden]: About — uncomment to restore
                // SettingsGroup(title = "About") {
                //     ZappRow(
                //         title = "About Zapp",
                //         icon = Icons.Default.Info,
                //         onClick = { /* route via TabsVM */ },
                //     )
                // }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ProfileCard(
    displayName: String,
    publicKey: String,
) {
    val c = ZappTheme.colors

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center,
        ) {
            ZashiQr(
                state =
                    QrState(
                        qrData = publicKey,
                        contentDescription = stringRes(R.string.settings_profile_qr_content_description),
                    ),
                modifier = Modifier.semantics { role = Role.Button },
                qrSize = 80.dp,
                contentPadding = PaddingValues(0.dp),
            )
        }

        BasicText(
            text = "@$displayName",
            style = ZappTheme.typography.sectionTitle.copy(color = c.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    val c = ZappTheme.colors
    ZappGroupHeader(text = title)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape),
    ) {
        content()
    }
    Spacer(Modifier.height(8.dp))
}
