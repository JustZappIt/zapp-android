package co.electriccoin.zcash.ui.screen.about.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappRowDivider
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.fixture.ConfigInfoFixture
import co.electriccoin.zcash.ui.fixture.VersionInfoFixture
import co.electriccoin.zcash.ui.screen.support.model.ConfigInfo
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER

@Composable
fun About(
    onBack: () -> Unit,
    configInfo: ConfigInfo,
    onPrivacyPolicy: () -> Unit,
    onTermsOfUse: () -> Unit,
    onLicense: () -> Unit,
    onSourceCode: () -> Unit,
    versionInfo: VersionInfo,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.displayCutout)
                ),
    ) {
        ZappScreenHeader(
            title = stringResource(id = R.string.about_title),
            right = {
                if (versionInfo.isDebuggable && !versionInfo.isRunningUnderTestService) {
                    DebugMenu(versionInfo, configInfo)
                }
            },
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            BasicText(
                modifier = Modifier.padding(horizontal = 18.dp),
                text = stringResource(id = R.string.about_subtitle),
                style = ZappTheme.typography.rowTitle.copy(color = c.text),
            )

            Spacer(Modifier.height(12.dp))

            BasicText(
                modifier = Modifier.padding(horizontal = 18.dp),
                text = stringResource(id = R.string.about_description, CURRENCY_TICKER),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )

            Spacer(Modifier.height(32.dp))

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .background(c.surface, RectangleShape)
                        .border(BorderStroke(1.dp, c.border), RectangleShape),
            ) {
                ZappRow(
                    title = stringResource(R.string.about_button_privacy_policy),
                    icon = Icons.Default.Policy,
                    onClick = onPrivacyPolicy,
                )
                ZappRowDivider(inset = true)
                ZappRow(
                    title = stringResource(R.string.terms_of_use),
                    icon = Icons.Default.Description,
                    onClick = onTermsOfUse,
                )
                ZappRowDivider(inset = true)
                ZappRow(
                    title = stringResource(R.string.about_button_license),
                    icon = Icons.Default.Gavel,
                    onClick = onLicense,
                )
                ZappRowDivider(inset = true)
                ZappRow(
                    title = stringResource(R.string.about_button_source_code),
                    icon = Icons.Default.Code,
                    onClick = onSourceCode,
                )
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(20.dp))

            BasicText(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                text = stringResource(R.string.about_legal_notice),
                style = ZappTheme.typography.rowSubtitle.copy(color = c.textSubtle),
            )

            Spacer(Modifier.height(12.dp))

            BasicText(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                text = stringResource(R.string.settings_version, versionInfo.versionName),
                style =
                    ZappTheme.typography.rowSubtitle.copy(
                        color = c.textSubtle,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    ),
            )

            Spacer(Modifier.height(20.dp))
        }

        ZappBottomActionBar(onBack = onBack)
    }
}

@Composable
private fun DebugMenu(
    versionInfo: VersionInfo,
    configInfo: ConfigInfo
) {
    val c = ZappTheme.colors
    Column(
        modifier = Modifier.testTag(AboutTag.DEBUG_MENU_TAG)
    ) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.about_overflow_menu_content_description),
                tint = c.text,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        BasicText(
                            stringResource(
                                id = R.string.about_debug_menu_app_name,
                                stringResource(id = R.string.app_name)
                            ),
                            style = ZappTheme.typography.rowSubtitle.copy(color = c.text),
                        )
                        BasicText(
                            stringResource(R.string.about_debug_menu_build, versionInfo.gitSha),
                            style = ZappTheme.typography.rowSubtitle.copy(color = c.text),
                        )
                        BasicText(
                            configInfo.toSupportString(),
                            style = ZappTheme.typography.rowSubtitle.copy(color = c.text),
                        )
                    }
                },
                onClick = {
                    expanded = false
                }
            )
        }
    }
}

@PreviewScreens
@Composable
private fun AboutPreview() =
    ProvideZappTheme {
        About(
            onBack = {},
            configInfo = ConfigInfoFixture.new(),
            onPrivacyPolicy = {},
            versionInfo = VersionInfoFixture.new(),
            onTermsOfUse = {},
            onLicense = {},
            onSourceCode = {}
        )
    }
