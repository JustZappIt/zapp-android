package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZappTheme

// ───────────────────────────────────────────────────────────────
// Phase 1 intro — Wallet
// ───────────────────────────────────────────────────────────────

@Composable
internal fun WalletPhaseIntro(
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    OnbScreen(
        step = 1,
        ghostNum = 1,
        badge = stringResource(R.string.onboarding_wallet_intro_badge),
        cta = stringResource(R.string.onboarding_continue),
        onCta = onContinue,
        showBack = true,
        onBack = onBack,
    ) {
        OnbHero(text = stringResource(R.string.onboarding_wallet_intro_title))
        Spacer(Modifier.height(16.dp))
        OnbSub(
            text = stringResource(R.string.onboarding_wallet_intro_sub),
            modifier = Modifier.fillMaxWidth(0.94f),
        )
        Spacer(Modifier.height(28.dp))
        OnbBulletRow(
            label = stringResource(R.string.onboarding_wallet_intro_bullet_create_label),
            sub = stringResource(R.string.onboarding_wallet_intro_bullet_create_sub),
            isFirst = true,
        )
        OnbBulletRow(
            label = stringResource(R.string.onboarding_wallet_intro_bullet_phrase_label),
            sub = stringResource(R.string.onboarding_wallet_intro_bullet_phrase_sub),
        )
    }
}

// ───────────────────────────────────────────────────────────────
// 06 · Wallet choice — Create / Restore (skip via dock)
// ───────────────────────────────────────────────────────────────

@Composable
internal fun WalletChoiceScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Progress bar
        Box(modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 20.dp)) {
            OnbProgress(step = 1)
        }
        // Body — hero at top, action card pinned to bottom (thumb zone)
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, top = 24.dp),
        ) {
            GhostNum(n = 1, modifier = Modifier.align(Alignment.TopEnd))
            Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                Eyebrow(stringResource(R.string.onboarding_wallet_choice_badge))
                Spacer(Modifier.height(14.dp))
                OnbHero(text = stringResource(R.string.wallet_empty_title))
                Spacer(Modifier.height(14.dp))
                OnbSub(stringResource(R.string.onboarding_wallet_choice_subtitle))
            }
            OnbActionListCard(
                actions =
                    listOf(
                        OnbAction(
                            icon = "✦",
                            label = stringResource(R.string.wallet_empty_create),
                            sub = stringResource(R.string.onboarding_wallet_choice_create_sub),
                            onClick = onCreate,
                            highlight = true,
                        ),
                        OnbAction(
                            icon = "⚿",
                            label = stringResource(R.string.wallet_empty_restore),
                            sub = stringResource(R.string.onboarding_wallet_choice_restore_sub),
                            onClick = onRestore,
                        ),
                    ),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
            )
        }
        // Bottom dock — back button only (bottom-left, per SKILL.md Pattern A)
        OnbBottomDock(
            cta = "",
            onCta = {},
            showBack = true,
            onBack = onBack,
            showCta = false,
        )
    }
}

// ───────────────────────────────────────────────────────────────
// 07 · Wallet recovery phrase
// ───────────────────────────────────────────────────────────────

@Composable
internal fun WalletSeedPhraseScreen(
    words: List<String>,
    onContinue: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    SeedRevealScreen(
        step = 1,
        title = stringResource(R.string.onboarding_wallet_seed_title),
        sub = stringResource(R.string.onboarding_wallet_seed_sub),
        words = words,
        onContinue = onContinue,
        onBack = onBack,
    )
}
