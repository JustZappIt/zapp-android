// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.deposits.DepositFees
import xyz.justzappit.offramp.deposits.DepositKind
import xyz.justzappit.offramp.deposits.DepositPass
import xyz.justzappit.offramp.deposits.DepositRecord
import xyz.justzappit.offramp.deposits.DepositState
import xyz.justzappit.offramp.deposits.DepositTerms
import xyz.justzappit.offramp.peer.Bps
import java.math.BigInteger
import kotlin.time.Duration

/**
 * What [pass] shows, newest first, as Veylor's `privateUsdDepositLines` has it: each deposit under way, each one that
 * landed while Railgun screens it, then each address holding money with none under way, received, under [minValue], or
 * not shielding after a refusal, a revert or a fee over the cap. [addressOf] gives each used address.
 */
internal fun depositLines(
    pass: DepositPass,
    minValue: BigInteger?,
    now: Long,
    screening: Duration,
    addressOf: (Int) -> Address,
): List<PrivateUsdDepositLine> {
    val underWay =
        pass.records
            .filterNot { it.state.isFinished }
            .map { it.index }
            .toSet()
    val deposits = pass.records.mapNotNull { it.line(now, screening) }
    val held =
        pass.scan
            ?.used
            .orEmpty()
            .filter { it.balance.micros.signum() > 0 && it.index !in underWay }
            .map { address ->
                val balance = address.balance.micros
                // Only the address's newest shield says whether it was turned down.
                val newest = pass.records.firstOrNull { it.index == address.index && it.kind == DepositKind.SHIELD }
                val isTurnedDown = newest?.state == DepositState.REFUSED || newest?.state == DepositState.REVERTED
                val phase =
                    when {
                        minValue != null && balance < minValue -> PrivateUsdDepositPhase.UNDER_MINIMUM
                        isTurnedDown || pass.isFeeOverCap -> PrivateUsdDepositPhase.NOT_SHIELDING
                        else -> PrivateUsdDepositPhase.RECEIVED
                    }
                PrivateUsdDepositLine(addressOf(address.index), phase, balance)
            }
    return deposits + held
}

/** What sending the larger of 20 dollars and the minimum brings under [terms]; none where the fee is over the cap. */
internal fun depositTerms(terms: DepositTerms): PrivateUsdDepositTerms {
    val sent = maxOf(EXAMPLE_SENT, terms.minValue)
    val example = DepositFees.capped(terms, sent)?.let { PrivateUsdDepositExample(sent, DepositFees.arrives(sent, it)) }
    return PrivateUsdDepositTerms(terms.minValue, terms.fee, Bps(terms.feeBps), example)
}

// A deposit under way, or one Railgun is still screening; none once it's spendable or never will be.
private fun DepositRecord.line(
    now: Long,
    screening: Duration,
): PrivateUsdDepositLine? =
    when (state) {
        DepositState.SENT -> {
            PrivateUsdDepositLine(from, PrivateUsdDepositPhase.SHIELDING, value.micros)
        }

        DepositState.SIGNED -> {
            val phase = if (waiting) PrivateUsdDepositPhase.WAITING else PrivateUsdDepositPhase.RECEIVED
            PrivateUsdDepositLine(from, phase, value.micros)
        }

        DepositState.LANDED -> {
            PrivateUsdDepositLine(from, PrivateUsdDepositPhase.SHIELDED, arrives).takeIf {
                now - (endedAt ?: 0) < screening.inWholeSeconds
            }
        }

        DepositState.REFUSED, DepositState.REVERTED, DepositState.REPLACED, DepositState.EXPIRED -> {
            null
        }
    }

// $20, in base units of a dollar token's 6 decimals.
private const val EXAMPLE_SENT_UNITS = 20_000_000L
private val EXAMPLE_SENT = BigInteger.valueOf(EXAMPLE_SENT_UNITS)
