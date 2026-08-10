package co.electriccoin.zcash.ui.screen.tabs.view

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.zapp.ZappSpeedDialAction
import co.electriccoin.zcash.ui.design.component.zapp.ZappSpeedDialFab
import co.electriccoin.zcash.ui.design.theme.colors.ZappNavBar

@Composable
internal fun PayActionSpeedDial(
    onPayMerchant: () -> Unit,
    onSend: () -> Unit,
    onSwap: () -> Unit,
    onReceive: () -> Unit,
    onBuyUsdc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ZappSpeedDialFab(
        expandContentDescription = stringResource(R.string.home_fab_actions_expand),
        collapseContentDescription = stringResource(R.string.home_fab_actions_collapse),
        actions =
            listOf(
                ZappSpeedDialAction(
                    icon = Icons.Default.Wallet,
                    label = stringResource(R.string.onramp_speed_dial_buy_usdc),
                    onClick = onBuyUsdc,
                ),
                ZappSpeedDialAction(
                    icon = Icons.Default.Storefront,
                    label = stringResource(R.string.home_button_pay_merchant),
                    onClick = onPayMerchant,
                ),
                ZappSpeedDialAction(
                    icon = Icons.AutoMirrored.Filled.CallMade,
                    label = stringResource(R.string.home_button_send),
                    onClick = onSend,
                ),
                ZappSpeedDialAction(
                    icon = Icons.Default.SwapHoriz,
                    label = stringResource(R.string.home_button_swap),
                    onClick = onSwap,
                ),
                ZappSpeedDialAction(
                    icon = Icons.AutoMirrored.Filled.CallReceived,
                    label = stringResource(R.string.home_button_receive),
                    onClick = onReceive,
                ),
            ),
        fabPadding =
            PaddingValues(
                end = 18.dp,
                bottom = navBarBottom + ZappNavBar.FAB_BOTTOM_PADDING_DP.dp,
            ),
        modifier = modifier,
    )
}
