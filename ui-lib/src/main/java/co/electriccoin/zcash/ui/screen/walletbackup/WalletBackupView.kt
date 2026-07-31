package co.electriccoin.zcash.ui.screen.walletbackup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.component.zapp.ZappBackButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.component.zapp.ZappSectionLabel
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.getString

@Composable
fun WalletBackupView(state: WalletBackupState) {
    if (shouldSecureScreen) {
        SecureScreen()
    }

    val c = ZappTheme.colors

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        ZappScreenHeader(
            title = stringResource(R.string.seed_recovery_title),
            left =
                state.onBack?.let { back ->
                    { ZappBackButton(onClick = back) }
                },
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            BasicText(
                text = stringResource(R.string.seed_recovery_header),
                style =
                    ZappTheme.typography.display.copy(
                        color = c.text,
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.8).sp,
                    ),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = stringResource(R.string.seed_recovery_description),
                style =
                    ZappTheme.typography.body.copy(
                        color = c.textMuted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                modifier = Modifier.fillMaxWidth(0.95f),
            )

            Spacer(Modifier.height(20.dp))

            SeedRevealBlock(
                seed = state.seed.seed,
                isRevealed = state.seed.isRevealed,
            )

            Spacer(Modifier.height(20.dp))

            BirthdayBlock(state = state.birthday)

            Spacer(Modifier.height(28.dp))
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            state.secondaryButton?.let { sb ->
                val ctx = LocalContext.current
                ZappButton(
                    text = sb.text.getString(ctx),
                    variant = ZappButtonVariant.Ghost,
                    enabled = sb.isEnabled && !sb.isLoading,
                    onClick = sb.onClick,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }
            val ctx = LocalContext.current
            ZappButton(
                text = state.primaryButton.text.getString(ctx),
                variant = ZappButtonVariant.Primary,
                enabled = state.primaryButton.isEnabled && !state.primaryButton.isLoading,
                onClick = state.primaryButton.onClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SeedRevealBlock(seed: String, isRevealed: Boolean) {
    val c = ZappTheme.colors
    val words = seed.split(" ", "\n").filter { it.isNotBlank() }

    Box(modifier = Modifier.fillMaxWidth()) {
        SeedGrid(
            words = words,
            isRevealed = isRevealed,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, c.border, RectangleShape)
                    .blur(if (isRevealed) 0.dp else 14.dp),
        )
        if (!isRevealed) {
            Column(
                modifier = Modifier.matchParentSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .background(c.text, RectangleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "👁",
                        style = ZappTheme.typography.body.copy(color = c.bg, fontSize = 18.sp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = stringResource(R.string.seed_recovery_tap_reveal_hint),
                    style =
                        ZappTheme.typography.button.copy(
                            color = c.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.2.sp,
                        ),
                )
            }
        }
    }
}

@Composable
private fun SeedGrid(words: List<String>, isRevealed: Boolean, modifier: Modifier = Modifier) {
    val c = ZappTheme.colors
    val maskedWord = stringResource(co.electriccoin.zcash.ui.design.R.string.general_masked_seed_word)
    val hiddenWordDescription = stringResource(co.electriccoin.zcash.ui.design.R.string.general_hidden_seed_word)
    val rows = words.chunked(3)
    Column(modifier = modifier) {
        rows.forEachIndexed { ri, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { wi, w ->
                    val idx = ri * 3 + wi
                    val cellBg = if (ri % 2 == 0) c.bg else c.surfaceAlt
                    Row(
                        modifier =
                            Modifier
                                .weight(1f)
                                .background(cellBg, RectangleShape)
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text = String.format("%02d", idx + 1),
                            style =
                                ZappTheme.typography.mono.copy(
                                    color = c.textSubtle,
                                    fontSize = 9.sp,
                                ),
                            modifier = Modifier.width(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        BasicText(
                            text = if (isRevealed) w else maskedWord,
                            style =
                                ZappTheme.typography.rowTitle.copy(
                                    color = c.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = (-0.1).sp,
                                ),
                            modifier =
                                if (isRevealed) {
                                    Modifier
                                } else {
                                    Modifier.clearAndSetSemantics { contentDescription = hiddenWordDescription }
                                },
                        )
                    }
                }
                repeat(3 - row.size) {
                    Box(modifier = Modifier.weight(1f).background(c.bg, RectangleShape))
                }
            }
        }
    }
}

@Composable
private fun BirthdayBlock(state: SeedSecretState) {
    val c = ZappTheme.colors
    val maskedValue = stringResource(co.electriccoin.zcash.ui.design.R.string.general_masked_seed_word)
    Column(modifier = Modifier.fillMaxWidth()) {
        ZappSectionLabel(text = state.title.getString(LocalContext.current))
        Spacer(Modifier.height(8.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(c.surfaceAlt, RectangleShape)
                    .border(1.dp, c.border, RectangleShape)
                    .blur(if (state.isRevealed) 0.dp else 12.dp)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            BasicText(
                text = if (state.isRevealed) state.text.getString(LocalContext.current) else maskedValue,
                style =
                    ZappTheme.typography.mono.copy(
                        color = c.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
        }
    }
}
