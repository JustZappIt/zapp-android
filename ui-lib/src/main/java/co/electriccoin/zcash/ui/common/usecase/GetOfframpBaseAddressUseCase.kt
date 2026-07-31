package co.electriccoin.zcash.ui.common.usecase

import xyz.justzappit.offramp.account.SmartOfframpAccountProvider

/**
 * The user's Base offramp wallet address (the ERC-4337 smart account that holds USDC and pays
 * merchants, not the owner key from [ExportP2pWalletKeyUseCase]). Derived from the wallet seed but
 * resolved through a factory call, so it suspends.
 */
class GetOfframpBaseAddressUseCase(
    private val accountProvider: SmartOfframpAccountProvider,
) {
    suspend operator fun invoke(): String = accountProvider.resolve().address.checksumHex
}
