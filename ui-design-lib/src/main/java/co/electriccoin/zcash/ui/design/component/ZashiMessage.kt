package co.electriccoin.zcash.ui.design.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle

@Composable
fun ZashiMessage(state: ZashiMessageState) {
    ZashiCard(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when (state.type) {
                        ZashiMessageState.Type.INFO -> ZashiColors.Utility.HyperBlue.utilityBlueDark50
                        ZashiMessageState.Type.WARNING -> ZashiColors.Utility.WarningYellow.utilityOrange50
                        ZashiMessageState.Type.ERROR -> ZashiColors.Utility.ErrorRed.utilityError50
                    },
            ),
        contentPadding = PaddingValues(16.dp)
    ) {
        Row {
            Image(
                painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
                contentDescription = null,
                colorFilter =
                    ColorFilter.tint(
                        when (state.type) {
                            ZashiMessageState.Type.INFO -> ZashiColors.Utility.HyperBlue.utilityBlueDark500
                            ZashiMessageState.Type.WARNING -> ZashiColors.Utility.WarningYellow.utilityOrange500
                            ZashiMessageState.Type.ERROR -> ZashiColors.Utility.ErrorRed.utilityError500
                        }
                    )
            )
            Spacer(12.dp)
            Column {
                Spacer(2.dp)
                Text(
                    text = state.title.getValue(),
                    style = ZashiTypography.textSm,
                    fontWeight = FontWeight.Medium,
                    color =
                        when (state.type) {
                            ZashiMessageState.Type.INFO -> ZashiColors.Utility.HyperBlue.utilityBlueDark700
                            ZashiMessageState.Type.WARNING -> ZashiColors.Utility.WarningYellow.utilityOrange700
                            ZashiMessageState.Type.ERROR -> ZashiColors.Utility.ErrorRed.utilityError700
                        }
                )
                Spacer(8.dp)
                Text(
                    text = state.text.getValue(),
                    style = ZashiTypography.textXs,
                    color =
                        when (state.type) {
                            ZashiMessageState.Type.INFO -> ZashiColors.Utility.HyperBlue.utilityBlueDark800
                            ZashiMessageState.Type.WARNING -> ZashiColors.Utility.WarningYellow.utilityOrange800
                            ZashiMessageState.Type.ERROR -> ZashiColors.Utility.ErrorRed.utilityError800
                        }
                )
            }
        }
    }
}

data class ZashiMessageState(
    val title: StringResource,
    val text: StyledStringResource,
    val type: Type
) {
    enum class Type {
        INFO,
        WARNING,
        ERROR
    }

    companion object {
        val preview =
            ZashiMessageState(
                stringRes("Title"),
                stringRes("Text").withStyle(),
                Type.INFO
            )
    }
}

@PreviewScreens
@Composable
private fun ZashiInfoMessagePreview() =
    ZcashTheme {
        ZashiMessage(ZashiMessageState.preview.copy(type = ZashiMessageState.Type.INFO))
    }

@PreviewScreens
@Composable
private fun ZashiWarningMessagePreview() =
    ZcashTheme {
        ZashiMessage(ZashiMessageState.preview.copy(type = ZashiMessageState.Type.WARNING))
    }

@PreviewScreens
@Composable
private fun ZashiErrorMessagePreview() =
    ZcashTheme {
        ZashiMessage(ZashiMessageState.preview.copy(type = ZashiMessageState.Type.ERROR))
    }
