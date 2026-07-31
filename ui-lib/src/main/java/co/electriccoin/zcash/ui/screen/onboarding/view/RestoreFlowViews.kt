@file:Suppress("TooManyFunctions")

package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.design.component.SeedTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import co.electriccoin.zcash.ui.design.component.TextSelection
import co.electriccoin.zcash.ui.design.component.ZashiSeedTextField
import co.electriccoin.zcash.ui.design.component.ZashiYearMonthWheelDatePicker
import co.electriccoin.zcash.ui.design.component.rememberSeedTextFieldHandle
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.onboarding.BirthdayMode
import java.time.YearMonth
import java.util.Locale

/** Body content takes ~94% of screen width so the hanging margin matches the Swiss layout. */
private const val BODY_WIDTH_FRACTION = 0.94f

// ── 1. Seed entry screen ────────────────────────────────────────

@Composable
internal fun RestoreSeedEntryScreen(
    seedState: SeedTextFieldState,
    suggestionsVisible: Boolean,
    suggestions: List<String>,
    isSeedValid: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SecureScreen()
    val c = ZappTheme.colors
    val handle = rememberSeedTextFieldHandle(seedState)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 20.dp)) {
            OnbProgress(step = 1)
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, top = 24.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Eyebrow(stringResource(R.string.restore_flow_seed_badge))
            Spacer(Modifier.height(14.dp))
            OnbHero(text = stringResource(R.string.restore_flow_seed_title))
            Spacer(Modifier.height(16.dp))
            OnbSub(
                text = stringResource(R.string.restore_flow_seed_sub),
                modifier = Modifier.fillMaxWidth(BODY_WIDTH_FRACTION),
            )
            Spacer(Modifier.height(28.dp))

            // ZashiSeedTextField uses Zashi design tokens internally. Wrap in
            // ZcashTheme so its internal color/typography lookups resolve.
            ZcashTheme {
                ZashiSeedTextField(state = seedState, handle = handle)
            }

            Spacer(Modifier.height(12.dp))
        }

        // Autocomplete suggestions bar
        if (suggestionsVisible && handle.selectedIndex >= 0) {
            val selectedText = handle.selectedText
            val filtered by remember(suggestions, selectedText) {
                derivedStateOf {
                    val trimmed = selectedText?.lowercase(Locale.US)?.trim().orEmpty()
                    if (trimmed.isBlank()) suggestions else suggestions.filter { it.startsWith(trimmed) }
                }
            }
            if (filtered.isNotEmpty() && !selectedText.isNullOrEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().background(c.bg),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered) { word ->
                        Box(
                            modifier =
                                Modifier
                                    .border(1.dp, c.border, RectangleShape)
                                    .clickable {
                                        val idx = handle.selectedIndex
                                        if (idx >= 0) {
                                            seedState.values[idx].onValueChange(
                                                SeedWordInnerTextFieldState(value = word, selection = TextSelection.End)
                                            )
                                            val nextIdx =
                                                seedState.values.withIndex().indexOfFirst { (i, f) ->
                                                    i > idx && (f.innerState.value.isBlank() || f.isError)
                                                }
                                            if (nextIdx != -1) handle.setSelectedIndex(nextIdx)
                                        }
                                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            BasicText(
                                text = word,
                                style =
                                    ZappTheme.typography.chip.copy(
                                        color = c.text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black,
                                    ),
                            )
                        }
                    }
                }
            }
        }

        OnbBottomDock(
            cta = stringResource(R.string.restore_button),
            onCta = onNext,
            ctaEnabled = isSeedValid,
            showBack = true,
            onBack = onBack,
        )
    }
}

// ── 2. Birthday height screen ───────────────────────────────────

@Composable
internal fun RestoreBirthdayScreen(
    birthdayText: String,
    onBirthdayChange: (String) -> Unit,
    birthdayMode: BirthdayMode,
    onBirthdayModeChange: (BirthdayMode) -> Unit,
    selectedYearMonth: YearMonth,
    onYearMonthChange: (YearMonth) -> Unit,
    isEstimating: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val c = ZappTheme.colors
    val borderColor = if (birthdayText.isNotEmpty()) c.text else c.border

    val ctaLabel =
        when {
            isEstimating -> stringResource(R.string.restore_flow_birthday_estimating)
            birthdayMode == BirthdayMode.DATE -> stringResource(R.string.restore_bd_height_btn)
            else -> stringResource(R.string.restore_bd_restore_btn)
        }

    OnbScreen(
        step = 1,
        ghostNum = 1,
        badge = stringResource(R.string.restore_flow_birthday_badge),
        cta = ctaLabel,
        onCta = onNext,
        ctaEnabled = !isEstimating,
        showBack = true,
        onBack = onBack,
    ) {
        OnbHero(text = stringResource(R.string.restore_flow_birthday_title))
        Spacer(Modifier.height(16.dp))
        OnbSub(
            text = stringResource(R.string.restore_flow_birthday_sub),
            modifier = Modifier.fillMaxWidth(BODY_WIDTH_FRACTION),
        )
        Spacer(Modifier.height(28.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            BirthdayModeTab(
                label = stringResource(R.string.restore_flow_birthday_mode_height),
                isSelected = birthdayMode == BirthdayMode.HEIGHT,
                onClick = { onBirthdayModeChange(BirthdayMode.HEIGHT) },
                modifier = Modifier.weight(1f),
            )
            BirthdayModeTab(
                label = stringResource(R.string.restore_flow_birthday_mode_date),
                isSelected = birthdayMode == BirthdayMode.DATE,
                onClick = { onBirthdayModeChange(BirthdayMode.DATE) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(20.dp))

        when (birthdayMode) {
            BirthdayMode.HEIGHT -> {
                BasicText(
                    text = stringResource(R.string.restore_flow_birthday_field_label),
                    style =
                        ZappTheme.typography.eyebrow.copy(
                            color = c.textSubtle,
                            fontSize = 10.sp,
                            letterSpacing = 1.8.sp,
                            fontWeight = FontWeight.Black,
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .border(width = 2.dp, color = borderColor, shape = RectangleShape)
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                ) {
                    BasicTextField(
                        value = birthdayText,
                        onValueChange = onBirthdayChange,
                        singleLine = true,
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        textStyle =
                            ZappTheme.typography.display.copy(
                                color = c.text,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.4).sp,
                            ),
                        decorationBox = { inner ->
                            if (birthdayText.isEmpty()) {
                                BasicText(
                                    text = stringResource(R.string.restore_flow_birthday_field_hint),
                                    style =
                                        ZappTheme.typography.display.copy(
                                            color = c.textSubtle,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = (-0.4).sp,
                                        ),
                                )
                            }
                            inner()
                        },
                    )
                }
            }

            BirthdayMode.DATE -> {
                ZcashTheme {
                    ZashiYearMonthWheelDatePicker(
                        selection = selectedYearMonth,
                        onSelectionChange = onYearMonthChange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                BasicText(
                    text = stringResource(R.string.restore_flow_birthday_date_note),
                    style =
                        ZappTheme.typography.body.copy(
                            color = c.textMuted,
                            fontSize = 12.sp,
                        ),
                )
            }
        }

        if (errorMessage != null) {
            Spacer(Modifier.height(16.dp))
            BasicText(
                text = errorMessage,
                style =
                    ZappTheme.typography.body.copy(
                        color = c.danger,
                        fontSize = 12.sp,
                    ),
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.clickable(onClick = onSkip).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = stringResource(R.string.restore_flow_birthday_skip),
                style =
                    ZappTheme.typography.button.copy(
                        color = c.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                    ),
            )
        }
    }
}

@Composable
private fun BirthdayModeTab(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZappTheme.colors
    Box(
        modifier =
            modifier
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) c.text else c.border,
                    shape = RectangleShape,
                ).clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style =
                ZappTheme.typography.button.copy(
                    color = if (isSelected) c.text else c.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                ),
        )
    }
}

// ── 3. Restore in-progress screen ───────────────────────────────

@Composable
internal fun RestoreInProgressScreen(
    errorMessage: String?,
    onRetry: (() -> Unit)?,
) {
    WalletEncryptingScreen(
        message = stringResource(R.string.restore_flow_loading_sub),
        errorMessage = errorMessage,
        onRetry = onRetry,
        retryHint = stringResource(R.string.restore_flow_loading_error),
        noRetryHint = stringResource(R.string.restore_flow_loading_error_no_retry),
        retryLabel = stringResource(R.string.restore_flow_loading_retry),
    )
}

// ── 4. Keep Zapp open screen ────────────────────────────────────

@Composable
internal fun KeepZappOpenScreen(
    keepScreenOn: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    onEnterApp: () -> Unit,
) {
    val c = ZappTheme.colors

    OnbScreen(
        step = 3,
        ghostNum = 3,
        badge = stringResource(R.string.restore_flow_keep_open_badge),
        cta = stringResource(R.string.restore_flow_keep_open_cta),
        onCta = onEnterApp,
        showBack = false,
    ) {
        OnbHero(text = stringResource(R.string.restore_flow_keep_open_title))
        Spacer(Modifier.height(16.dp))
        OnbSub(
            text = stringResource(R.string.restore_flow_keep_open_sub),
            modifier = Modifier.fillMaxWidth(BODY_WIDTH_FRACTION),
        )
        Spacer(Modifier.height(28.dp))

        OnbBulletRow(
            label = stringResource(R.string.restore_flow_keep_open_bullet_1),
            isFirst = true,
        )
        OnbBulletRow(
            label = stringResource(R.string.restore_flow_keep_open_bullet_2),
        )

        Spacer(Modifier.height(20.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleKeepScreenOn),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .height(48.dp)
                        .width(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(20.dp)
                            .height(20.dp)
                            .background(if (keepScreenOn) c.accent else c.bg, RectangleShape)
                            .border(2.dp, if (keepScreenOn) c.accent else c.borderStrong, RectangleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (keepScreenOn) {
                        BasicText(
                            text = "\u2713",
                            style =
                                ZappTheme.typography.button.copy(
                                    color = c.onAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            BasicText(
                text = stringResource(R.string.restore_flow_keep_open_checkbox),
                style =
                    ZappTheme.typography.body.copy(
                        color = c.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                    ),
            )
        }
    }
}
