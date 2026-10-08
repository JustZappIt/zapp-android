// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.repository.BaseBalance
import co.electriccoin.zcash.ui.common.repository.BaseBalanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.scan
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Whether a money flow has anything to spend. [EMPTY] is only claimed when the wallet has fully
 * synced and still holds nothing, so a wallet that is syncing, restoring or awaiting a balance is
 * [UNKNOWN] and keeps its usual form.
 */
enum class Funding { UNKNOWN, EMPTY, FUNDED }

/**
 * The money flows (Send, Swap out, Gift, Pay a merchant, Add funds to Base) use this to show
 * "Add funds" up front instead of letting someone fill a form they cannot pay for.
 */
class ObserveFundingUseCase(
    private val accountDataSource: AccountDataSource,
    private val walletSnapshotDataSource: WalletSnapshotDataSource,
    private val baseBalanceRepository: BaseBalanceRepository,
) {
    /** Funding for flows that spend ZEC only. */
    fun zec(): Flow<Funding> =
        combine(
            accountDataSource.selectedAccount,
            walletSnapshotDataSource.observe(),
        ) { account, snapshot -> account?.totalBalance to snapshot }
            .scan(Funding.UNKNOWN) { previous, (total, snapshot) ->
                nextZecFunding(
                    previous = previous,
                    total = total,
                    status = snapshot?.status,
                    isRestoring = snapshot?.restoringState == WalletRestoringState.RESTORING,
                )
            }.distinctUntilChanged()

    /** Funding for flows that can pay from USDC on Base as well as ZEC (Pay a merchant). */
    fun zecOrBaseUsdc(): Flow<Funding> =
        combine(zec(), baseBalanceRepository.balance) { zec, base ->
            withBaseUsdc(zec, base)
        }.distinctUntilChanged()
}

/**
 * A synced wallet re-enters SYNCING on every new block, so once [Funding.EMPTY] has been seen it
 * holds through those catch-ups instead of the "Add funds" panel blinking off each time.
 */
internal fun nextZecFunding(
    previous: Funding,
    total: Zatoshi?,
    status: Synchronizer.Status?,
    isRestoring: Boolean,
): Funding =
    when {
        total == null -> Funding.UNKNOWN
        total.value > 0L -> Funding.FUNDED
        isRestoring -> Funding.UNKNOWN
        status == Synchronizer.Status.SYNCED -> Funding.EMPTY
        previous == Funding.EMPTY -> Funding.EMPTY
        else -> Funding.UNKNOWN
    }

internal fun withBaseUsdc(zec: Funding, base: BaseBalance): Funding =
    when {
        zec == Funding.FUNDED -> Funding.FUNDED
        base is BaseBalance.Loaded && base.balance > Usdc6.ZERO -> Funding.FUNDED
        zec == Funding.EMPTY && base is BaseBalance.Loaded -> Funding.EMPTY
        else -> Funding.UNKNOWN
    }
