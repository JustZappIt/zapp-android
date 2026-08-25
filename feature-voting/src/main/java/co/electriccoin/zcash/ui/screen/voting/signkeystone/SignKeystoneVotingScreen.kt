package co.electriccoin.zcash.ui.screen.voting.signkeystone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.appbar.ZashiTopAppBarTags
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationBottomSheet
import co.electriccoin.zcash.ui.design.component.ZashiInScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenProgressIndicator
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionBottomSheet
import co.electriccoin.zcash.ui.screen.voting.VoteButton
import co.electriccoin.zcash.ui.screen.voting.VoteConfirmationBottomSheet
import co.electriccoin.zcash.ui.screen.voting.component.VoteAppBar
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignKeystoneVotingScreen(args: SignKeystoneVotingArgs) {
    val vm = koinViewModel<SignKeystoneVotingVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    val bottomSheetState by vm.bottomSheetState.collectAsStateWithLifecycle()
    val skipBottomSheetState by vm.skipBottomSheetState.collectAsStateWithLifecycle()
    val errorSheet by vm.errorSheet.collectAsStateWithLifecycle()
    val scanNoticeSheet by vm.scanNoticeSheet.collectAsStateWithLifecycle()

    BackHandler {
        if (state != null) {
            state?.onBack?.invoke()
        } else {
            vm.onScreenBack()
        }
    }

    when {
        state != null -> AuthorizeVoteSignKeystoneView(requireNotNull(state))
        else -> SignKeystoneVotingLoadingView(onBack = vm::onScreenBack)
    }

    VoteConfirmationBottomSheet(state = errorSheet)
    VoteConfirmationBottomSheet(state = scanNoticeSheet)
    SignKeystoneTransactionBottomSheet(state = bottomSheetState)
    SkipKeystoneBundlesBottomSheet(state = skipBottomSheetState)
}

@Serializable
data class SignKeystoneVotingArgs(
    val roundIdHex: String
)

data class SkipKeystoneBundlesBottomSheetState(
    override val onBack: () -> Unit,
    val message: StringResource,
    val skipButton: ButtonState,
    val cancelButton: ButtonState
) : ModalBottomSheetState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkipKeystoneBundlesBottomSheet(
    state: SkipKeystoneBundlesBottomSheetState?,
    modifier: Modifier = Modifier
) {
    ZashiInScreenModalBottomSheet(
        state = state,
        modifier = modifier
    ) { sheetState ->
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.sign_keystone_voting_skip_remaining_title),
                style = ZappTheme.typography.sectionTitle,
                color = ZappTheme.colors.text,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = sheetState.message.getValue(),
                style = ZappTheme.typography.rowSubtitle,
                color = ZappTheme.colors.textMuted,
            )
            Spacer(Modifier.height(32.dp))
            VoteButton(
                state = sheetState.skipButton,
                modifier = Modifier.fillMaxWidth(),
                variant = ZappButtonVariant.Danger,
            )
            Spacer(Modifier.height(8.dp))
            VoteButton(
                state = sheetState.cancelButton,
                modifier = Modifier.fillMaxWidth(),
                variant = ZappButtonVariant.Secondary,
            )
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
        }
    }
}

@Composable
private fun SignKeystoneVotingLoadingView(onBack: () -> Unit) {
    val c = ZappTheme.colors
    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        containerColor = c.bg,
        topBar = { VoteAppBar(title = stringResource(R.string.coinVote_common_confirmation)) },
        bottomBar = { ZappBottomActionBar(onBack = onBack) },
    ) { padding ->
        ZappScreenProgressIndicator(modifier = Modifier.padding(padding))
    }
}

@PreviewScreens
@Composable
private fun SignKeystoneVotingLoadingPreview() =
    ProvideZappTheme { SignKeystoneVotingLoadingView(onBack = {}) }

@PreviewScreens
@Composable
private fun SkipKeystoneBundlesBottomSheetPreview() =
    ZcashTheme {
        SkipKeystoneBundlesBottomSheet(
            state =
                SkipKeystoneBundlesBottomSheetState(
                    onBack = {},
                    message = stringRes("You still have unsigned bundles. Do you want to skip the remaining ones?"),
                    skipButton = ButtonState(text = stringRes("Skip remaining"), onClick = {}),
                    cancelButton = ButtonState(text = stringRes("Cancel"), onClick = {})
                )
        )
    }
