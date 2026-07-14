package co.electriccoin.zcash.ui.screen.migration.complete

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.GradientBgScaffold
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

data class MigrationCompleteState(
    val totalTransferred: StringResource,
    val remainingDust: StringResource?,
    val transfersProgress: StringResource,
    val duration: StringResource,
    val onDone: () -> Unit,
)

@Serializable
data object MigrationCompleteArgs

@Composable
fun MigrationCompleteScreen() {
    val vm = koinViewModel<MigrationCompleteVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(state) { MigrationCompleteView(it) }
}

@Composable
fun MigrationCompleteView(state: MigrationCompleteState) {
    GradientBgScaffold(
        startColor = ZashiColors.Utility.SuccessGreen.utilitySuccess100,
        endColor = ZashiColors.Surfaces.bgPrimary,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .scaffoldPadding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_fist_punch),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Migration Complete",
                    style = ZashiTypography.header5,
                    fontWeight = FontWeight.SemiBold,
                    color = ZashiColors.Text.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Your ZEC is now in the Ironwood pool.",
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textTertiary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(ZashiColors.Surfaces.bgSecondary)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SummaryRow(label = "Total transferred", value = state.totalTransferred.getValue())
                    state.remainingDust?.let { dust ->
                        SummaryRow(label = "Remaining dust", value = dust.getValue())
                    }
                    SummaryRow(label = "Transfers", value = state.transfersProgress.getValue())
                    SummaryRow(label = "Duration", value = state.duration.getValue())
                }
                state.remainingDust?.let { dust ->
                    Spacer(Modifier.height(20.dp))
                    DustDisclaimer(dustAmount = dust.getValue())
                }
            }
            ZashiButton(
                state = ButtonState(text = stringRes("Got it"), onClick = state.onDone),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = ZashiTypography.textSm,
            fontWeight = FontWeight.Medium,
            color = ZashiColors.Text.textPrimary,
        )
    }
}

@Composable
private fun DustDisclaimer(dustAmount: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ZashiColors.Surfaces.bgSecondary)
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Dust balance remaining",
                style = ZashiTypography.textSm,
                fontWeight = FontWeight.Medium,
                color = ZashiColors.Text.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$dustAmount stayed in Orchard — the amount is below the transfer threshold.",
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
            contentDescription = null,
            tint = ZashiColors.Text.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@PreviewScreens
@Composable
private fun PreviewWithDust() = ZcashTheme {
    MigrationCompleteView(
        state = MigrationCompleteState(
            totalTransferred = stringRes("12.458 ZEC"),
            remainingDust = stringRes("0.00031 ZEC"),
            transfersProgress = stringRes("5 of 5 sent"),
            duration = stringRes("~24 hours"),
            onDone = {},
        )
    )
}

@PreviewScreens
@Composable
private fun PreviewNoDust() = ZcashTheme {
    MigrationCompleteView(
        state = MigrationCompleteState(
            totalTransferred = stringRes("12.458 ZEC"),
            remainingDust = null,
            transfersProgress = stringRes("5 of 5 sent"),
            duration = stringRes("~24 hours"),
            onDone = {},
        )
    )
}
