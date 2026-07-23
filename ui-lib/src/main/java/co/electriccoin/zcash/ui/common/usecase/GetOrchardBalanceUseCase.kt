package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The real, spendable Orchard balance for the currently selected wallet account — the balance
 * migration actually moves to Ironwood.
 *
 * `WalletAccount.unified` is Orchard-only despite its name (see its own `TODO [#26]` comment) —
 * this use case is the single seam over that quirk, so migration screens read "the Orchard
 * balance" without each needing to know where that quirk lives or where the value comes from.
 */
class GetOrchardBalanceUseCase(
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
) {
    suspend operator fun invoke(): Zatoshi = getSelectedWalletAccount().unified.balance.available

    fun observe(): Flow<Zatoshi?> = getSelectedWalletAccount.observe().map { it?.unified?.balance?.available }
}
