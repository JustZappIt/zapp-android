package co.electriccoin.zcash.ui.screen.swap.peer.order

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.zapp.ZappBorderedCard
import co.electriccoin.zcash.ui.design.component.zapp.ZappBottomActionBar
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappButtonVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappSummaryRow
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes

@Composable
internal fun PeerOrderView(state: PeerOrderState) {
    val c = ZappTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = GUTTER.dp, vertical = VERTICAL_PADDING.dp),
        ) {
            BasicText(
                text = state.headline.getValue(),
                style = ZappTheme.typography.display.copy(color = c.text),
            )
            state.supporting?.let {
                Spacer(Modifier.height(GAP_SM.dp))
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.body.copy(color = c.textMuted),
                )
            }
            state.lastUpdated?.let {
                Spacer(Modifier.height(GAP_SM.dp))
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.caption.copy(color = c.textMuted),
                )
            }

            state.soldProgress?.let {
                Spacer(Modifier.height(GAP_LG.dp))
                BasicText(
                    text = it.getValue(),
                    style = ZappTheme.typography.sectionTitle.copy(color = c.text),
                )
            }

            if (state.rows.isNotEmpty()) {
                Spacer(Modifier.height(GAP_LG.dp))
                ZappBorderedCard {
                    state.rows.forEachIndexed { index, row ->
                        if (index > 0) Spacer(Modifier.height(GAP_SM.dp))
                        FactRow(row)
                    }
                }
            }

            if (state.buyers.isNotEmpty()) {
                Spacer(Modifier.height(GAP_LG.dp))
                BasicText(
                    text = stringResource(R.string.peer_order_buyers_heading),
                    style = ZappTheme.typography.eyebrow.copy(color = c.textMuted),
                )
                Spacer(Modifier.height(GAP_SM.dp))
                ZappBorderedCard {
                    state.buyers.forEachIndexed { index, buyer ->
                        if (index > 0) Spacer(Modifier.height(GAP_MD.dp))
                        BuyerRow(buyer)
                    }
                }
            }

            state.secondaryAction?.let { action ->
                Spacer(Modifier.height(GAP_LG.dp))
                ZappButton(
                    text = action.text.getValue(),
                    variant = ZappButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = action.onClick,
                )
            }

            state.explorerUrl?.let { url ->
                val uriHandler = LocalUriHandler.current
                Spacer(Modifier.height(GAP_MD.dp))
                ZappButton(
                    text = stringResource(R.string.peer_order_view_explorer),
                    variant = ZappButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { uriHandler.openUri(url) },
                )
            }
        }
        ZappBottomActionBar(
            onBack = state.onBack,
            primaryAction =
                state.primaryAction?.let { btn ->
                    {
                        ZappButton(
                            text = btn.text.getValue(),
                            enabled = btn.isEnabled,
                            loading = btn.isLoading,
                            variant = ZappButtonVariant.Primary,
                            modifier = Modifier.weight(1f).padding(start = BOTTOM_BAR_GAP.dp),
                            onClick = btn.onClick,
                        )
                    }
                },
        )
    }
}

@Composable
private fun FactRow(row: PeerOrderRow) {
    val uriHandler = LocalUriHandler.current
    if (row.url == null) {
        ZappSummaryRow(label = row.label.getValue(), value = row.value.getValue())
    } else {
        Box(
            modifier =
                Modifier
                    .clickable { uriHandler.openUri(row.url) }
                    .semantics { role = Role.Button },
        ) {
            ZappSummaryRow(
                label = row.label.getValue(),
                value = row.value.getValue(),
                valueColor = ZappTheme.colors.accent,
            )
        }
    }
}

@Composable
private fun BuyerRow(buyer: PeerOrderBuyerRow) {
    val c = ZappTheme.colors
    val uriHandler = LocalUriHandler.current
    val dot =
        when (buyer.tone) {
            PeerBuyerTone.Settled -> c.success
            PeerBuyerTone.Live -> c.accent
            PeerBuyerTone.Dropped -> c.textSubtle
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    buyer.url
                        ?.let { Modifier.clickable { uriHandler.openUri(it) }.semantics { role = Role.Button } }
                        ?: Modifier,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(DOT_SIZE.dp)
                    .background(dot, RectangleShape),
        )
        Spacer(Modifier.width(GAP_MD.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = buyer.title.getValue(),
                style = ZappTheme.typography.body.copy(color = c.text, fontWeight = FontWeight.Medium),
            )
            BasicText(
                text = buyer.subtitle.getValue(),
                style = ZappTheme.typography.caption.copy(color = c.textMuted),
            )
        }
    }
}

private const val GUTTER = 18
private const val VERTICAL_PADDING = 16
private const val GAP_SM = 6
private const val GAP_MD = 10
private const val GAP_LG = 20
private const val BOTTOM_BAR_GAP = 12
private const val DOT_SIZE = 8

@PreviewScreens
@Composable
private fun PeerOrderWaitingPreview() =
    ZcashTheme {
        PeerOrderView(
            state =
                PeerOrderState(
                    headline = stringRes("Waiting for a buyer"),
                    supporting = stringRes("You can close the app. The order stays open on chain."),
                    soldProgress = null,
                    rows =
                        listOf(
                            PeerOrderRow(stringRes("Opened"), stringRes("13 Aug 2026, 04:23")),
                            PeerOrderRow(stringRes("Open for"), stringRes("34m 26s")),
                            PeerOrderRow(stringRes("On offer"), stringRes("250 USDC")),
                            PeerOrderRow(stringRes("Free to withdraw"), stringRes("250 USDC")),
                            PeerOrderRow(stringRes("Rate"), stringRes("1 USDC ≈ 0.91 EUR")),
                            PeerOrderRow(stringRes("Currencies"), stringRes("EUR, GBP, USD")),
                            PeerOrderRow(stringRes("Buyers can take"), stringRes("50 to 250 USDC")),
                            PeerOrderRow(stringRes("Paid to"), stringRes("Revolut · andrew1abc")),
                            PeerOrderRow(
                                label = stringRes("Opened on Base"),
                                value = stringRes("0x992d032f…aac3"),
                                url = "https://basescan.org",
                            ),
                        ),
                    buyers = emptyList(),
                    lastUpdated = stringRes("Last updated 0 minutes ago"),
                    primaryAction = ButtonState(text = stringRes("Withdraw"), onClick = {}),
                    secondaryAction = null,
                    explorerUrl = "https://peerlytics.xyz/explorer/deposit/0xabc_1987",
                    confirmation = null,
                    onBack = {},
                ),
        )
    }

@PreviewScreens
@Composable
private fun PeerOrderPartialPreview() =
    ZcashTheme {
        PeerOrderView(
            state =
                PeerOrderState(
                    headline = stringRes("A buyer is paying you"),
                    supporting = stringRes("Usually a couple of minutes once they start"),
                    soldProgress = stringRes("110 of 250 sold"),
                    rows =
                        listOf(
                            PeerOrderRow(stringRes("Opened"), stringRes("13 Aug 2026, 04:23")),
                            PeerOrderRow(stringRes("Open for"), stringRes("2h 41m")),
                            PeerOrderRow(stringRes("First buyer after"), stringRes("34m 26s")),
                            PeerOrderRow(stringRes("On offer"), stringRes("140 USDC")),
                            PeerOrderRow(stringRes("Free to withdraw"), stringRes("90 USDC")),
                            PeerOrderRow(stringRes("Buyers"), stringRes("3 · 1 paid · 1 released")),
                        ),
                    buyers =
                        listOf(
                            PeerOrderBuyerRow(
                                title = stringRes("50 USDC"),
                                subtitle = stringRes("Owes 45.10 EUR · 5h 12m left"),
                                tone = PeerBuyerTone.Live,
                                url = null,
                            ),
                            PeerOrderBuyerRow(
                                title = stringRes("60 USDC"),
                                subtitle = stringRes("Paid 54.05 EUR in 2m 30s"),
                                tone = PeerBuyerTone.Settled,
                                url = "https://basescan.org",
                            ),
                            PeerOrderBuyerRow(
                                title = stringRes("40 USDC"),
                                subtitle = stringRes("Backed out after 38s"),
                                tone = PeerBuyerTone.Dropped,
                                url = "https://basescan.org",
                            ),
                        ),
                    lastUpdated = stringRes("Last updated 1 minutes ago"),
                    primaryAction = ButtonState(text = stringRes("Withdraw"), onClick = {}),
                    secondaryAction = null,
                    explorerUrl = "https://peerlytics.xyz/explorer/deposit/0xabc_1987",
                    confirmation = null,
                    onBack = {},
                ),
        )
    }
