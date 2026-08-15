package co.electriccoin.zcash.ui.screen.migration.howitworks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.zapp.zappAccentButtonColors
import co.electriccoin.zcash.ui.design.component.zapp.zappTopAppBarColors
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import co.electriccoin.zcash.ui.design.R as DesignR

@Serializable
data object MigrationHowItWorksArgs

@Composable
fun MigrationHowItWorksScreen() {
    val vm = koinViewModel<MigrationHowItWorksVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(
        state = state,
        loading = { isLoading -> if (isLoading && state.content == null) ZappScreenProgressIndicator() },
    ) { s ->
        BackHandler { s.onBack() }
        MigrationHowItWorksView(s)
    }
}

@Composable
fun MigrationHowItWorksView(state: MigrationHowItWorksState) {
    BlankBgScaffold(
        containerColor = ZappTheme.colors.bg,
        topBar = {
            ZashiSmallTopAppBar(
                colors = zappTopAppBarColors(),
                navigationAction = { ZashiTopAppBarBackNavigation(onBack = state.onBack) },
                regularActions = {},
            )
        }
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .scaffoldPadding(padding),
        ) {
            Text(
                text = stringRes(DesignR.string.migrationHowItWorks_title).getValue(),
                style = ZappTheme.typography.screenTitle,
                fontWeight = FontWeight.SemiBold,
                color = ZappTheme.colors.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringRes(DesignR.string.migrationHowItWorks_subtitle).getValue(),
                style = ZappTheme.typography.body,
                color = ZappTheme.colors.textMuted,
            )
            Spacer(Modifier.height(32.dp))
            HowItWorksStep(
                icon = co.electriccoin.zcash.ui.R.drawable.ic_migration_coins_swap,
                title = stringRes(DesignR.string.migrationHowItWorks_splitScheduleTitle).getValue(),
                description = stringRes(DesignR.string.migrationHowItWorks_splitScheduleDescription).getValue(),
            )
            Spacer(Modifier.height(16.dp))
            HowItWorksStep(
                icon = co.electriccoin.zcash.migration.R.drawable.ic_migration_check_square_broken,
                title = stringRes(DesignR.string.migrationHowItWorks_approveOnceTitle).getValue(),
                description = stringRes(DesignR.string.migrationHowItWorks_approveOnceDescription).getValue(),
            )
            Spacer(Modifier.height(16.dp))
            HowItWorksStep(
                icon = co.electriccoin.zcash.migration.R.drawable.ic_migration_notif_bell_ringing,
                title = stringRes(DesignR.string.migrationHowItWorks_ifSomethingFailsTitle).getValue(),
                description = stringRes(DesignR.string.migrationHowItWorks_ifSomethingFailsDescription).getValue(),
            )
            Spacer(Modifier.height(16.dp))
            HowItWorksStep(
                icon = co.electriccoin.zcash.migration.R.drawable.ic_migration_calendar,
                title = stringRes(DesignR.string.migrationHowItWorks_largeBalanceTitle).getValue(),
                description = stringRes(DesignR.string.migrationHowItWorks_largeBalanceDescription).getValue(),
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Icon(
                    painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
                    contentDescription = null,
                    tint = ZappTheme.colors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringRes(DesignR.string.migrationHowItWorks_disclaimer).getValue(),
                    style = ZappTheme.typography.caption,
                    color = ZappTheme.colors.textMuted,
                )
            }
            Spacer(Modifier.height(20.dp))
            ZashiButton(
                state =
                    ButtonState(
                        text = stringRes(DesignR.string.migration_common_continue),
                        onClick = state.onContinue
                    ),
                modifier = Modifier.fillMaxWidth(),
                defaultPrimaryColors = zappAccentButtonColors(),
            )
        }
    }
}

@Composable
private fun HowItWorksStep(icon: Int, title: String, description: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = ZappTheme.colors.text,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = ZappTheme.typography.body,
                fontWeight = FontWeight.SemiBold,
                color = ZappTheme.colors.text,
            )
            Text(
                text = description,
                style = ZappTheme.typography.caption,
                color = ZappTheme.colors.textMuted,
            )
        }
    }
}

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        MigrationHowItWorksView(
            state = MigrationHowItWorksState(onContinue = {}, onBack = {})
        )
    }
