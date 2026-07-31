package co.electriccoin.zcash.ui.screen.tabs.view

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.screen.onboarding.view.Eyebrow
import co.electriccoin.zcash.ui.screen.onboarding.view.OnbAction
import co.electriccoin.zcash.ui.screen.onboarding.view.OnbActionListCard
import co.electriccoin.zcash.ui.screen.onboarding.view.OnbHero
import co.electriccoin.zcash.ui.screen.onboarding.view.OnbSub
import co.electriccoin.zcash.ui.screen.onboarding.view.WalletSeedPhraseScreen
import org.koin.androidx.compose.koinViewModel

/** Local state machine for the wallet tab when the user post-skipped wallet creation. */
private enum class CreatePhase { Idle, RevealingSeed }

@Composable
internal fun WalletTabContent(
    onRestoreWallet: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit = {},
    walletViewModel: WalletViewModel = koinViewModel(),
) {
    val c = ZappTheme.colors
    val secretState by walletViewModel.secretState.collectAsStateWithLifecycle()
    val seedWords by walletViewModel.currentSeedWords.collectAsStateWithLifecycle()

    // Tracks whether the user just tapped "Create" inside the empty state. When
    // they have, we keep showing the seed-reveal screen even after the wallet
    // is persisted (secretState == READY) so they get a chance to back up the
    // phrase before dropping into the wallet home.
    var createPhase by rememberSaveable { mutableStateOf(CreatePhase.Idle) }

    // The seed-reveal sub-screen owns its own bottom CTA dock; the floating
    // nav pill would otherwise overlap it. Tell the parent to hide the nav
    // pill while we're in that sub-screen.
    LaunchedEffect(createPhase) {
        onFullscreenChange(createPhase == CreatePhase.RevealingSeed)
    }

    Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
        when {
            secretState == SecretState.LOADING -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.accent)
                }
            }

            createPhase == CreatePhase.RevealingSeed -> {
                WalletSeedPhraseScreen(
                    words = seedWords.orEmpty(),
                    onBack = { createPhase = CreatePhase.Idle },
                    onContinue = { createPhase = CreatePhase.Idle },
                )
            }

            secretState == SecretState.NONE -> {
                WalletEmptyState(
                    onCreate = {
                        walletViewModel.createNewWallet()
                        createPhase = CreatePhase.RevealingSeed
                    },
                    onRestore = onRestoreWallet,
                )
            }

            else -> {
                WalletHomeView()
            }
        }
    }
}

@Composable
private fun WalletEmptyState(
    onCreate: () -> Unit,
    onRestore: () -> Unit,
) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Eyebrow(text = stringResource(R.string.wallet_empty_eyebrow))
        }
        Spacer(Modifier.height(28.dp))
        OnbHero(text = stringResource(R.string.wallet_empty_title))
        Spacer(Modifier.height(14.dp))
        OnbSub(
            text = stringResource(R.string.wallet_empty_subtitle),
            modifier = Modifier.fillMaxWidth(0.94f),
        )
        Spacer(Modifier.height(32.dp))
        OnbActionListCard(
            actions =
                listOf(
                    OnbAction(
                        icon = "✦",
                        label = stringResource(R.string.wallet_empty_create),
                        sub = stringResource(R.string.wallet_empty_create_sub),
                        onClick = onCreate,
                        highlight = true,
                    ),
                    OnbAction(
                        icon = "⚿",
                        label = stringResource(R.string.wallet_empty_restore),
                        sub = stringResource(R.string.wallet_empty_restore_sub),
                        onClick = onRestore,
                    ),
                ),
        )
    }
}
