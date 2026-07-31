@file:Suppress("TooManyFunctions")

package co.electriccoin.zcash.ui.screen.securitysettings.view

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.design.component.zapp.ZappGroupHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappRow
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.screen.onboarding.view.BioScanScreen
import co.electriccoin.zcash.ui.screen.onboarding.view.PinVerifyScreen
import co.electriccoin.zcash.ui.screen.securitysettings.SecuritySettingsState
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────
// Top-level dispatcher
// ─────────────────────────────────────────────────────────────

@Composable
internal fun SecuritySettingsView(
    state: SecuritySettingsState,
    pinError: Boolean,
    lockoutSeconds: Int,
    bioError: StringResource?,
    isEnrollingBio: Boolean,
    onTabSelected: (AuthMethod) -> Unit,
    onSaveChanges: () -> Unit,
    onPinSubmit: (String) -> Unit,
    onNewPinConfirmed: (String) -> Unit,
    onBioEnroll: () -> Unit,
    onClearSuccessMessage: () -> Unit,
    onBack: () -> Unit,
) {
    when (state) {
        is SecuritySettingsState.Menu -> {
            AppLockHub(
                state = state,
                onTabSelected = onTabSelected,
                onSaveChanges = onSaveChanges,
                onClearSuccessMessage = onClearSuccessMessage,
                onBack = onBack,
            )
        }

        is SecuritySettingsState.VerifyingCurrentPin -> {
            PinVerifyScreen(
                hasError = pinError,
                showBack = true,
                lockoutSecondsRemaining = lockoutSeconds,
                onPinSubmit = onPinSubmit,
                onCancel = onBack,
            )
        }

        is SecuritySettingsState.SettingNewPin -> {
            ChangePinScreen(
                onNewPinConfirmed = onNewPinConfirmed,
                onBack = onBack,
            )
        }

        SecuritySettingsState.SettingNewBio -> {
            BioScanScreen(
                isEnrolling = isEnrollingBio,
                errorMessage = bioError,
                onEnroll = onBioEnroll,
                onCancel = onBack,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Hub screen: illustration + segmented selector + action row
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppLockHub(
    state: SecuritySettingsState.Menu,
    onTabSelected: (AuthMethod) -> Unit,
    onSaveChanges: () -> Unit,
    onClearSuccessMessage: () -> Unit,
    onBack: () -> Unit,
) {
    val c = ZappTheme.colors
    val snackbarHostState = remember { SnackbarHostState() }
    val successMessage = state.successMessage?.getValue()

    LaunchedEffect(successMessage) {
        if (successMessage != null) {
            snackbarHostState.showSnackbar(successMessage)
            onClearSuccessMessage()
        }
    }

    // Scaffold used only for SnackbarHost — content insets handled manually.
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
            ZappScreenHeader(title = stringResource(R.string.security_settings_title))

            // Illustration fills remaining vertical space
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AuthMethodIllustration(selectedTab = state.selectedTab)
            }

            // Segmented selector
            AppLockTabSelector(
                selectedTab = state.selectedTab,
                onTabSelected = onTabSelected,
            )

            // Contextual action row
            ZappGroupHeader(text = stringResource(R.string.security_settings_actions_header))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .background(c.surface, RectangleShape)
                        .border(BorderStroke(1.dp, c.border), RectangleShape),
            ) {
                if (state.selectedTab == AuthMethod.PIN) {
                    ZappRow(
                        title = stringResource(R.string.security_settings_change_pin_row_title),
                        subtitle = stringResource(R.string.security_settings_change_pin_row_subtitle),
                        icon = Icons.Default.Lock,
                        iconTint = c.accentText,
                        iconBackground = c.accentSoft,
                        onClick = onSaveChanges,
                    )
                } else {
                    ZappRow(
                        title = stringResource(R.string.security_settings_reenroll_bio_row_title),
                        subtitle = stringResource(R.string.security_settings_reenroll_bio_row_subtitle),
                        icon = Icons.Default.Fingerprint,
                        iconTint = c.accentText,
                        iconBackground = c.accentSoft,
                        onClick = onSaveChanges,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bottom dock — Pattern B2
            HubBottomDock(onBack = onBack, onSaveChanges = onSaveChanges)
        }
    }
}

@Composable
private fun AppLockTabSelector(
    selectedTab: AuthMethod,
    onTabSelected: (AuthMethod) -> Unit,
) {
    val c = ZappTheme.colors
    val tabs =
        listOf(
            AuthMethod.PIN to stringResource(R.string.security_settings_tab_pin),
            AuthMethod.BIOMETRIC to stringResource(R.string.security_settings_tab_biometric),
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape)
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEach { (key, label) ->
            val isSelected = selectedTab == key
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .background(if (isSelected) c.accent else Color.Transparent, RectangleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = c.accent),
                            onClick = { onTabSelected(key) },
                        ).semantics(mergeDescendants = true) {
                            contentDescription = label
                            role = Role.Tab
                        },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = label,
                    style =
                        ZappTheme.typography.caption.copy(
                            color = if (isSelected) c.onAccent else c.textMuted,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal,
                        ),
                )
            }
        }
    }
}

@Composable
private fun AuthMethodIllustration(selectedTab: AuthMethod) {
    val c = ZappTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 28.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(100.dp)
                    .background(c.accentSoft, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selectedTab == AuthMethod.PIN) Icons.Default.Lock else Icons.Default.Fingerprint,
                contentDescription = null,
                tint = c.accentText,
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        BasicText(
            text =
                stringResource(
                    if (selectedTab == AuthMethod.PIN) {
                        R.string.security_settings_tab_pin
                    } else {
                        R.string.security_settings_tab_biometric
                    }
                ),
            style =
                ZappTheme.typography.display.copy(
                    color = c.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                ),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text =
                stringResource(
                    if (selectedTab == AuthMethod.PIN) {
                        R.string.security_settings_illustration_pin_body
                    } else {
                        R.string.security_settings_illustration_bio_body
                    }
                ),
            style =
                ZappTheme.typography.body.copy(
                    color = c.textMuted,
                    textAlign = TextAlign.Center,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HubBottomDock(onBack: () -> Unit, onSaveChanges: () -> Unit) {
    val c = ZappTheme.colors
    val backContentDescription = stringResource(R.string.security_settings_back_content_description)
    val saveChangesContentDescription = stringResource(R.string.security_settings_save_changes_content_description)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp)
                .padding(bottom = 8.dp)
                .background(c.surface)
                .border(BorderStroke(1.dp, c.border), RectangleShape),
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 72.dp, height = 52.dp)
                    .border(BorderStroke(1.dp, c.border), RectangleShape)
                    .clickable(onClick = onBack)
                    .semantics {
                        contentDescription = backContentDescription
                        role = Role.Button
                    },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = stringResource(R.string.security_settings_back_glyph),
                style =
                    ZappTheme.typography.button.copy(
                        color = c.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .background(c.accent, RectangleShape)
                    .clickable(onClick = onSaveChanges)
                    .semantics {
                        contentDescription = saveChangesContentDescription
                        role = Role.Button
                    },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = stringResource(R.string.security_settings_save_changes_button),
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

// ─────────────────────────────────────────────────────────────
// Change PIN screen (settings context — no onboarding chrome)
// ─────────────────────────────────────────────────────────────

@Composable
private fun ChangePinScreen(
    onNewPinConfirmed: (String) -> Unit,
    onBack: () -> Unit,
) {
    SecureScreen()

    val c = ZappTheme.colors
    val backContentDescription = stringResource(R.string.security_settings_back_content_description)
    var isConfirmPhase by rememberSaveable { mutableStateOf(false) }
    var firstPin by rememberSaveable { mutableStateOf("") }
    var currentInput by rememberSaveable { mutableStateOf("") }
    var mismatchError by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(currentInput) {
        if (currentInput.length == 6) {
            if (!isConfirmPhase) {
                firstPin = currentInput
                currentInput = ""
                isConfirmPhase = true
            } else {
                if (currentInput == firstPin) {
                    onNewPinConfirmed(currentInput)
                } else {
                    mismatchError = true
                    currentInput = ""
                }
            }
        }
    }

    LaunchedEffect(mismatchError) {
        if (mismatchError) {
            delay(1500)
            isConfirmPhase = false
            firstPin = ""
            currentInput = ""
            mismatchError = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, top = 24.dp),
        ) {
            // Title + subtitle anchored to top-left — mirrors PinVerifyScreen
            Column(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(),
            ) {
                Spacer(Modifier.height(14.dp))
                BasicText(
                    text =
                        stringResource(
                            if (isConfirmPhase) {
                                R.string.security_settings_change_pin_confirm_title
                            } else {
                                R.string.security_settings_change_pin_change_title
                            }
                        ),
                    style =
                        ZappTheme.typography.display.copy(
                            color = c.text,
                            fontSize = 42.sp,
                            lineHeight = 44.sp,
                            letterSpacing = (-1.8).sp,
                            fontWeight = FontWeight.Black,
                        ),
                )
                Spacer(Modifier.height(14.dp))
                BasicText(
                    text =
                        stringResource(
                            when {
                                mismatchError -> R.string.security_settings_change_pin_mismatch
                                isConfirmPhase -> R.string.security_settings_change_pin_confirm_subtitle
                                else -> R.string.security_settings_change_pin_enter_subtitle
                            }
                        ),
                    style =
                        ZappTheme.typography.body.copy(
                            color = if (mismatchError) c.danger else c.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 22.sp,
                        ),
                )
            }

            // Dots + keypad anchored to the bottom — mirrors PinVerifyScreen
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PinDotsRow(filledCount = currentInput.length, hasError = mismatchError)
                Spacer(Modifier.height(28.dp))
                PinKeypadGrid(
                    modifier = Modifier.fillMaxWidth(),
                    onKey = { key ->
                        if (!mismatchError) {
                            when {
                                key == "⌫" -> if (currentInput.isNotEmpty()) currentInput = currentInput.dropLast(1)
                                currentInput.length < 6 -> currentInput += key
                            }
                        }
                    },
                )
            }
        }

        // Bottom dock: ← | progress counter (always disabled — auto-submits at 6)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 8.dp)
                    .background(c.surface)
                    .border(BorderStroke(1.dp, c.border), RectangleShape),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(width = 72.dp, height = 52.dp)
                        .border(BorderStroke(1.dp, c.border), RectangleShape)
                        .clickable(onClick = {
                            if (isConfirmPhase) {
                                isConfirmPhase = false
                                currentInput = ""
                                firstPin = ""
                            } else {
                                onBack()
                            }
                        })
                        .semantics {
                            contentDescription = backContentDescription
                            role = Role.Button
                        },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = stringResource(R.string.security_settings_back_glyph),
                    style =
                        ZappTheme.typography.button.copy(
                            color = c.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                        ),
                )
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .background(c.surfaceAlt, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = stringResource(R.string.security_settings_change_pin_progress_fmt, currentInput.length),
                    style =
                        ZappTheme.typography.button.copy(
                            color = c.textSubtle,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.2.sp,
                        ),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Shared PIN UI primitives (settings context)
// ─────────────────────────────────────────────────────────────

@Composable
private fun PinDotsRow(filledCount: Int, hasError: Boolean) {
    val c = ZappTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(6) { i ->
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .background(
                            when {
                                hasError -> c.danger
                                i < filledCount -> c.text
                                else -> c.border
                            },
                            RectangleShape,
                        ),
            )
        }
    }
}

@Composable
private fun PinKeypadGrid(modifier: Modifier = Modifier, onKey: (String) -> Unit) {
    val c = ZappTheme.colors
    val deleteContentDescription = stringResource(R.string.security_settings_keypad_delete_content_description)
    val rows =
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(null, "0", "⌫"),
        )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    if (key == null) {
                        Box(modifier = Modifier.weight(1f).height(60.dp))
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(60.dp)
                                    .border(1.dp, c.border, RectangleShape)
                                    .clickable(onClick = { onKey(key) })
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = if (key == "⌫") deleteContentDescription else key
                                        role = Role.Button
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                text = key,
                                style =
                                    ZappTheme.typography.button.copy(
                                        color = c.text,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Black,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}
