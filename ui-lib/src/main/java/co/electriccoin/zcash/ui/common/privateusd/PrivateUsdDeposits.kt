// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.peer.Bps
import java.math.BigInteger

/**
 * Public USDC sent to the wallet's deposit addresses, which Zapp shields into the Railgun balance on its own.
 *
 * The Receive page's USD tab shows [state], and the Shielded USD page's "i" mentions deposits while there is one.
 * Until deposits are built, [NoPrivateUsdDeposits] stands in and the tab stays hidden.
 */
interface PrivateUsdDepositsRepository {
    /**
     * Null while this build takes no deposits: the deployment pins no Deposits contract, or the relayer's terms have
     * no `deposits`. Otherwise what's known so far, with terms still loading, so the tab doesn't appear late.
     */
    val state: StateFlow<PrivateUsdDeposits?>

    /** Looks at the deposit addresses again: the USD tab asks as it opens, then on a timer while it shows. */
    fun refresh()
}

data class PrivateUsdDeposits(
    /** What deposits take: USDC on mainnet, tUSD on testnet. */
    val token: PrivateUsdToken,
    /** The lowest address never shielded from, to share; null until it's derived. */
    val address: Address?,
    /** Set when [address] couldn't be derived. */
    val isAddressFailed: Boolean = false,
    /** The relayer's live terms; null until they're read. */
    val terms: PrivateUsdDepositTerms? = null,
    /** Set when [terms] couldn't be read: money sent meanwhile waits at its address. */
    val isTermsFailed: Boolean = false,
    /** Each address holding money, or with a deposit under way or still being screened, newest first. */
    val lines: List<PrivateUsdDepositLine> = emptyList(),
)

/** The relayer's `deposits` terms, in [PrivateUsdDeposits.token]'s base units. */
data class PrivateUsdDepositTerms(
    /** The least a deposit moves. */
    val minValue: BigInteger,
    /** The relayer's fee before its percentage. */
    val fee: BigInteger,
    /** The relayer's percentage of a deposit. */
    val feeBps: Bps,
    /** What a deposit brings after both fees, the relayer's and Railgun's; null leaves the example out. */
    val example: PrivateUsdDepositExample? = null,
)

/** A deposit of [sent], of which [arrives] lands in the shielded balance. */
data class PrivateUsdDepositExample(
    val sent: BigInteger,
    val arrives: BigInteger,
)

/** What one address holding money is doing. */
data class PrivateUsdDepositLine(
    val address: Address,
    val phase: PrivateUsdDepositPhase,
    /** What the address holds, what a deposit under way moves, or what a shielded one brought, in base units. */
    val amount: BigInteger,
)

enum class PrivateUsdDepositPhase {
    /** Holds at least the minimum, not posted yet. */
    RECEIVED,

    /** Holds less than the minimum: it stays the address shown. */
    UNDER_MINIMUM,

    /** The relayer asked to wait over 30 s, or couldn't be reached. */
    WAITING,

    /** Posted, until it's mined. */
    SHIELDING,

    /** Mined, while Railgun screens it. */
    SHIELDED,

    /** Refused, or the relayer's fee is over the cap: not shielding for now. */
    NOT_SHIELDING,
}

/** No deposits in this build yet: no deployment pins a Deposits contract, so the USD tab stays hidden. */
object NoPrivateUsdDeposits : PrivateUsdDepositsRepository {
    override val state: StateFlow<PrivateUsdDeposits?> = MutableStateFlow<PrivateUsdDeposits?>(null).asStateFlow()

    override fun refresh() = Unit
}
