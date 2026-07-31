package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKException
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKProvider
import com.keystone.module.ZcashAccounts
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder

class ParseKeystoneUrToZashiAccountsUseCase(
    private val keystoneSDKProvider: KeystoneSDKProvider,
) {
    @Throws(InvalidKeystoneSignInQRException::class)
    operator fun invoke(urRaw: String): ZcashAccounts {
        val ur = URDecoder.decode(urRaw)
        return getAccountsFromKeystone(ur)
    }

    @Throws(InvalidKeystoneSignInQRException::class)
    private fun getAccountsFromKeystone(ur: UR): ZcashAccounts =
        try {
            keystoneSDKProvider.parseZcashAccounts(ur).also {
                Twig.debug { "=========> progress: $it" }
            }
        } catch (e: KeystoneSDKException) {
            throw InvalidKeystoneSignInQRException(e)
        }
}
