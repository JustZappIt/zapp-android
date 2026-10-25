// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.receive

import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo

/** The two ways dollars come in, in the order their tabs show. */
internal enum class PrivateUsdReceiveTab {
    /** Public USDC from an exchange or any wallet, to a deposit address. */
    USD,

    /** A private send from another Railgun wallet, to the 0zk address. */
    SHIELDED,
}

internal data class PrivateUsdReceiveState(
    val tab: PrivateUsdReceiveTab,
    /** Null hides the USD tab, and with it the choice: only [shielded] shows. */
    val usd: PrivateUsdReceiveUsdState?,
    val shielded: PrivateUsdReceiveShieldedState,
    val onSelectTab: (PrivateUsdReceiveTab) -> Unit,
    val info: PrivateUsdInfo,
    val onBack: () -> Unit,
)

/** The 0zk address, for sends from a Railgun wallet: private, with no fee. */
internal data class PrivateUsdReceiveShieldedState(
    /** Which wallets can send here, and on which network. */
    val notice: StringResource,
    val address: PrivateUsdReceiveAddressState,
    val note: StringResource,
)

/** A deposit address for public USDC, which Zapp shields on its own for a fee. */
internal data class PrivateUsdReceiveUsdState(
    /** The one token and network it takes: anything else is lost or stuck. */
    val notice: StringResource,
    val address: PrivateUsdReceiveAddressState,
    /** What each address holding money is doing, newest first. */
    val lines: List<PrivateUsdReceiveLineState>,
    /** The minimum and both fees, from the live terms; null until they're read. */
    val fees: PrivateUsdReceiveFeesState?,
    /** In [fees]' place: they're loading, or they couldn't be read. */
    val feesStatus: StringResource?,
    /** How it's shielded, when it can be spent, and what's public. */
    val notes: List<StringResource>,
)

/** An address to share whole: shown, as a QR code of the plain string, and copied with a tap. */
internal data class PrivateUsdReceiveAddressState(
    /** Null while it loads, or when it couldn't: [status] says which. */
    val value: String?,
    val status: StringResource?,
    val label: StringResource,
    val qrDescription: StringResource,
    val copyDescription: StringResource,
    val isCopied: Boolean,
    val onCopy: () -> Unit,
)

internal data class PrivateUsdReceiveFeesState(
    val minimum: StringResource,
    /** A fixed part and a percentage: "$0.11 + 0.1%". */
    val relayerFee: StringResource,
    val railgunFee: StringResource,
    /** What a deposit brings after both fees, when it's known. */
    val example: StringResource?,
)

internal data class PrivateUsdReceiveLineState(
    val text: StringResource,
    val tone: PrivateUsdReceiveLineTone,
)

internal enum class PrivateUsdReceiveLineTone {
    /** Money in, or a deposit under way. */
    ACTIVE,

    /** Waiting: on more money, on the network, or after a refusal. */
    MUTED,

    /** Shielded. */
    DONE,
}
