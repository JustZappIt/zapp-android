package co.electriccoin.zcash.ui.common.usecase

import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.account.OfframpAccountProvider

class ExportP2pWalletKeyUseCase(
    private val offrampAccountProvider: OfframpAccountProvider,
) {
    suspend operator fun invoke(): P2pWalletKey {
        val key = offrampAccountProvider.nextOfframpAccount()
        val privateKeyBytes = key.exportPrivateKeyBytes()
        return try {
            P2pWalletKey(
                address = key.address.checksumHex,
                privateKeyHex = "0x" + privateKeyBytes.toHex(),
            )
        } finally {
            privateKeyBytes.fill(0)
        }
    }
}

class P2pWalletKey(
    val address: String,
    val privateKeyHex: String,
) {
    // toString omits the private key so the secret can't leak through incidental logging.
    override fun toString(): String = "P2pWalletKey(address=$address)"
}
