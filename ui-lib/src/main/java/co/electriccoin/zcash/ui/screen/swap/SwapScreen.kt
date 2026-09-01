package co.electriccoin.zcash.ui.screen.swap

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.appbar.ZashiTopAppBarVM
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOfframpBaseAddressUseCase
import co.electriccoin.zcash.ui.design.component.ZashiScreenModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappCopyableAddress
import co.electriccoin.zcash.ui.design.component.zapp.ZappScreenHeader
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.LocalNavController
import co.electriccoin.zcash.ui.design.util.tryRequestFocus
import co.electriccoin.zcash.ui.screen.swap.upi.PrescannedMerchantQr
import co.electriccoin.zcash.ui.screen.swap.upi.UpiOfframpBody
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import xyz.justzappit.offramp.p2p.CurrencyCode

@Composable
fun SwapScreen(
    tab: SwapTab = SwapTab.SWAP,
    offrampCurrency: CurrencyCode = CurrencyCode.Inr,
    prescannedMerchantQr: PrescannedMerchantQr = PrescannedMerchantQr.EMPTY,
) {
    val navigationRouter = koinInject<NavigationRouter>()
    var showOfframpInfo by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ZappTheme.colors.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        ZappScreenHeader(
            title =
                stringResource(
                    when (tab) {
                        SwapTab.SWAP -> R.string.swap_title
                        SwapTab.OFFRAMP -> R.string.swap_tab_offramp
                    },
                ),
            right =
                if (tab == SwapTab.OFFRAMP) {
                    {
                        IconButton(onClick = { showOfframpInfo = true }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painter = painterResource(co.electriccoin.zcash.ui.design.R.drawable.ic_info),
                                contentDescription = stringResource(R.string.upi_offramp_info_content_description),
                                tint = ZappTheme.colors.text,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else {
                    null
                },
        )

        when (tab) {
            SwapTab.SWAP -> {
                SwapBody()
            }

            SwapTab.OFFRAMP -> {
                UpiOfframpBody(
                    onBack = { navigationRouter.back() },
                    currency = offrampCurrency,
                    prescannedMerchantQr = prescannedMerchantQr,
                )
            }
        }
    }

    if (showOfframpInfo) {
        OfframpInfoSheet(onDismiss = { showOfframpInfo = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfframpInfoSheet(onDismiss: () -> Unit) {
    val c = ZappTheme.colors
    ZashiScreenModalBottomSheet(onDismissRequest = onDismiss) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText(
                    text = stringResource(R.string.upi_offramp_info_title),
                    style = ZappTheme.typography.sectionTitle.copy(color = c.text),
                )
                Image(
                    painter = painterResource(R.drawable.ic_p2p_logo),
                    contentDescription = null,
                    modifier = Modifier.height(20.dp),
                )
            }
            BasicText(
                text = stringResource(R.string.upi_offramp_info_body_flow),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            BasicText(
                text = stringResource(R.string.upi_offramp_info_body_privacy),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            BasicText(
                text = stringResource(R.string.upi_offramp_estimate_disclaimer),
                style = ZappTheme.typography.body.copy(color = c.textMuted),
            )
            OfframpBaseAddress()
            ZappButton(
                text = stringResource(co.electriccoin.zcash.ui.design.R.string.general_ok),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

/**
 * The smart account merchant payments are settled from. Shown here because it is the address a
 * top-up has to land on, and the offramp screen otherwise never names it.
 */
@Composable
private fun OfframpBaseAddress() {
    val getBaseAddress = koinInject<GetOfframpBaseAddressUseCase>()
    val copyToClipboard = koinInject<CopyToClipboardUseCase>()
    val address by produceState<String?>(initialValue = null) {
        value = runCatching { getBaseAddress() }.getOrNull()
    }
    var isCopied by remember { mutableStateOf(false) }
    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(COPY_FEEDBACK_MS)
            isCopied = false
        }
    }
    address?.let {
        ZappCopyableAddress(
            label = stringResource(R.string.settings_p2p_payment_method_info_base_label),
            address = it,
            copyContentDescription =
                stringResource(
                    if (isCopied) {
                        R.string.settings_p2p_payment_method_info_copied_content_description
                    } else {
                        R.string.settings_p2p_payment_method_info_copy_content_description
                    },
                ),
            isCopied = isCopied,
            onCopy = {
                copyToClipboard(it)
                isCopied = true
            },
        )
    }
}

private const val COPY_FEEDBACK_MS = 2_000L

@Composable
private fun SwapBody() {
    val vm = koinViewModel<SwapVM>()
    val appBarVM = koinViewModel<ZashiTopAppBarVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    val cancelState by vm.cancelState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    var hasBeenAutofocused by rememberSaveable {
        val isSwapFirstScreen =
            navController
                .currentBackStackEntry
                ?.destination
                ?.route == SwapArgs::class.qualifiedName
        mutableStateOf(!isSwapFirstScreen)
    }
    val appBarState by appBarVM.state.collectAsStateWithLifecycle()
    state?.let {
        SwapView(
            state = it,
            appBarState = appBarState,
            onSideEffect = { amountFocusRequester ->
                if (!hasBeenAutofocused) {
                    hasBeenAutofocused = amountFocusRequester.tryRequestFocus() ?: true
                }
            },
            embeddedInTabHost = true,
        )
    }
    BackHandler(state != null) { state?.onBack?.invoke() }
    SwapCancelView(cancelState)
}

@Serializable
data object SwapArgs

/**
 * Opens the swap screen directly on the offramp tab (the pay-merchant flow). [currencyCode] selects
 * the corridor (INR/BRL/IDR); passed as the [CurrencyCode.code] string to stay nav-serialization-safe.
 *
 * The `prescanned*` fields carry a merchant QR already scanned by the home Pay-tab scanner so the
 * flow skips the mid-order re-scan. [prescannedFiatAmount] is a plain-decimal string (BigDecimal
 * isn't nav-serializable) and null for an open/payer-defined QR.
 */
@Serializable
data class UpiOfframpArgs(
    val currencyCode: String = CurrencyCode.Inr.code,
    val prescannedPayload: String? = null,
    val prescannedPaymentAddress: String? = null,
    val prescannedFiatAmount: String? = null,
)
