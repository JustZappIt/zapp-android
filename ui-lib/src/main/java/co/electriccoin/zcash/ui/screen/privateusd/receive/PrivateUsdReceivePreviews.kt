// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.receive

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo

@PreviewScreens
@Composable
private fun ShieldedOnlyPreview() =
    ZcashTheme {
        PrivateUsdReceiveView(state = previewState(usd = null, tab = PrivateUsdReceiveTab.SHIELDED))
    }

@PreviewScreens
@Composable
private fun ShieldedTabPreview() =
    ZcashTheme {
        PrivateUsdReceiveView(state = previewState(usd = previewUsd(), tab = PrivateUsdReceiveTab.SHIELDED))
    }

@PreviewScreens
@Composable
private fun UsdPreview() =
    ZcashTheme {
        PrivateUsdReceiveView(state = previewState(usd = previewUsd(), tab = PrivateUsdReceiveTab.USD))
    }

@PreviewScreens
@Composable
private fun UsdLoadingPreview() =
    ZcashTheme {
        PrivateUsdReceiveView(
            state =
                previewState(
                    usd =
                        previewUsd().copy(
                            address = previewAddress(value = null, status = "Loading your deposit address…"),
                            lines = emptyList(),
                            fees = null,
                            feesStatus = stringRes("Loading the fees…"),
                        ),
                    tab = PrivateUsdReceiveTab.USD,
                )
        )
    }

@PreviewScreens
@Composable
private fun UsdFeesFailedPreview() =
    ZcashTheme {
        PrivateUsdReceiveView(
            state =
                previewState(
                    usd =
                        previewUsd().copy(
                            lines =
                                listOf(
                                    PrivateUsdReceiveLineState(
                                        stringRes(
                                            "Received $0.50. Waiting for the network; your money is safe at your " +
                                                "deposit address.",
                                        ),
                                        PrivateUsdReceiveLineTone.MUTED,
                                    ),
                                ),
                            fees = null,
                            feesStatus =
                                stringRes("Couldn't load the fees. Money sent here waits safely at this address."),
                        ),
                    tab = PrivateUsdReceiveTab.USD,
                )
        )
    }

private fun previewState(
    usd: PrivateUsdReceiveUsdState?,
    tab: PrivateUsdReceiveTab,
) = PrivateUsdReceiveState(
    tab = tab,
    usd = usd,
    shielded =
        PrivateUsdReceiveShieldedState(
            notice = stringRes("Only from a Railgun wallet on Ethereum"),
            address =
                previewAddress(
                    value =
                        "0zk1qyrs4qyrd08p6uep0fc2y8njktgcpezts3rpaq6q0ln948ecjkw8prv7j6f" +
                            "e3z53llz8ursderja0juwv5pgnv8x5klmmwkv8q38h9n704h4d4qjyw7n5qk68nx",
                    status = null,
                    label = "Your Railgun address",
                ),
            note = stringRes("Shielded USD sent from another Railgun wallet arrives here privately, with no fee."),
        ),
    onSelectTab = {},
    info = PrivateUsdInfo(title = stringRes("Receiving shielded USD")),
    onBack = {},
)

private fun previewUsd() =
    PrivateUsdReceiveUsdState(
        notice = stringRes("Send only USDC on Ethereum. Other tokens, or USDC on other networks, are lost or stuck."),
        address =
            previewAddress(
                value = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf",
                status = null,
                label = "Your deposit address",
            ),
        lines =
            listOf(
                PrivateUsdReceiveLineState(
                    stringRes("Received $25.00. Shielding…"),
                    PrivateUsdReceiveLineTone.ACTIVE,
                ),
                PrivateUsdReceiveLineState(
                    stringRes("Shielded $19.82, available in about 1 h"),
                    PrivateUsdReceiveLineTone.DONE,
                ),
            ),
        fees =
            PrivateUsdReceiveFeesState(
                minimum = stringRes("$1.00"),
                relayerFee = stringRes("$0.11 + 0.1%"),
                railgunFee = stringRes("0.25%"),
                example = stringRes("Sending $20.00 brings about $19.82."),
            ),
        feesStatus = null,
        notes =
            listOf(
                stringRes("Zapp shields it on its own while Zapp is open and unlocked. There's nothing to tap."),
                stringRes("It can be spent about 1 h after it arrives, once Railgun has screened it."),
                stringRes("The sender and the amount are public on Ethereum. Your balance is private from then on."),
            ),
    )

private fun previewAddress(
    value: String?,
    status: String?,
    label: String = "Your deposit address",
) = PrivateUsdReceiveAddressState(
    value = value,
    status = status?.let { stringRes(it) },
    label = stringRes(label),
    qrDescription = stringRes("QR code"),
    copyDescription = stringRes("Copy"),
    isCopied = false,
    onCopy = {},
)
