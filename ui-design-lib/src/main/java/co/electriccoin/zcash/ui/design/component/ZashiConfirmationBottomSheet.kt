package co.electriccoin.zcash.ui.design.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZashiConfirmationBottomSheet(state: ZashiConfirmationState?) {
    ZashiScreenModalBottomSheet(state = state) { innerState, contentPadding ->
        ConfirmationContent(
            modifier = Modifier.weight(1f, false),
            state = innerState,
            contentPadding = contentPadding
        )
    }
}

data class ZashiConfirmationState(
    val icon: Int,
    val title: StringResource,
    val message: StringResource,
    val primaryAction: ButtonState,
    val secondaryAction: ButtonState? = null,
    override val onBack: () -> Unit,
    val style: ZashiConfirmationStyle = ZashiConfirmationStyle.DEFAULT,
) : ModalBottomSheetState {
    companion object {
        val preview =
            ZashiConfirmationState(
                icon = android.R.drawable.ic_dialog_alert,
                title = stringRes("Preview title"),
                message = stringRes("Preview message"),
                primaryAction = ButtonState.preview,
                onBack = {},
            )
    }
}

/**
 * Upstream also rounds the sheet's corners for [UNVERIFIED_POLL_WARNING]; the fork keeps every
 * sheet on the shared square silhouette, so the style only carries the part that is about meaning
 * rather than shape — a warning leads with the cautious action instead of the confirming one.
 */
enum class ZashiConfirmationStyle {
    DEFAULT,
    UNVERIFIED_POLL_WARNING,
}

@Composable
private fun ConfirmationContent(
    state: ZashiConfirmationState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = contentPadding.calculateBottomPadding()
                ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(state.icon),
            contentDescription = null
        )
        Spacer(12.dp)
        Text(
            text = state.title.getValue(),
            style = ZashiTypography.textXl,
            color = ZashiColors.Text.textPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(12.dp)
        Text(
            text = state.message.getValue(),
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textTertiary,
            textAlign = TextAlign.Center
        )
        Spacer(32.dp)
        val actions =
            if (state.style == ZashiConfirmationStyle.UNVERIFIED_POLL_WARNING) {
                listOfNotNull(state.secondaryAction, state.primaryAction)
            } else {
                listOfNotNull(state.primaryAction, state.secondaryAction)
            }
        actions.forEachIndexed { index, action ->
            if (index > 0) Spacer(8.dp)
            ZashiButton(
                modifier = Modifier.fillMaxWidth(),
                state = action,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreens
@Composable
private fun ZashiConfirmationBottomSheetPreview() =
    ZcashTheme {
        ZashiConfirmationBottomSheet(
            state =
                ZashiConfirmationState(
                    icon = android.R.drawable.ic_dialog_alert,
                    title = stringRes("Are you sure?"),
                    message = stringRes("This action cannot be undone."),
                    primaryAction =
                        ButtonState(
                            text = stringRes("Confirm"),
                            style = ButtonStyle.DESTRUCTIVE2,
                            onClick = {}
                        ),
                    secondaryAction =
                        ButtonState(
                            text = stringRes("Cancel"),
                            style = ButtonStyle.PRIMARY,
                            onClick = {}
                        ),
                    onBack = {}
                )
        )
    }
