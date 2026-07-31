package co.electriccoin.zcash.ui.common.provider

import com.keystone.module.DecodeResult
import com.keystone.module.ZcashAccounts
import com.keystone.sdk.KeystoneSDK
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder

interface KeystoneSDKProvider {
    @Throws(KeystoneSDKException::class)
    fun decodeQR(result: String): DecodeResult

    @Throws(KeystoneSDKException::class)
    fun resetQRDecoder()

    @Throws(KeystoneSDKException::class)
    fun parseZcashAccounts(ur: UR): ZcashAccounts

    @Throws(KeystoneSDKException::class)
    fun generatePczt(pczt: ByteArray): UREncoder

    @Throws(KeystoneSDKException::class)
    fun parsePczt(ur: UR): ByteArray
}

class KeystoneSDKException(
    cause: Throwable
) : Exception(cause)

class KeystoneSDKProviderImpl : KeystoneSDKProvider {
    private val sdk: KeystoneSDK by lazy { KeystoneSDK() }

    @Suppress("TooGenericExceptionCaught")
    override fun decodeQR(result: String): DecodeResult =
        try {
            sdk.decodeQR(result)
        } catch (e: Exception) {
            throw KeystoneSDKException(e)
        }

    @Suppress("TooGenericExceptionCaught")
    override fun resetQRDecoder() {
        try {
            sdk.resetQRDecoder()
        } catch (e: Exception) {
            throw KeystoneSDKException(e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun parseZcashAccounts(ur: UR): ZcashAccounts =
        try {
            sdk.parseZcashAccounts(ur)
        } catch (e: Exception) {
            throw KeystoneSDKException(e)
        }

    @Suppress("TooGenericExceptionCaught")
    override fun generatePczt(pczt: ByteArray): UREncoder =
        try {
            sdk.zcash.generatePczt(pczt)
        } catch (e: Exception) {
            throw KeystoneSDKException(e)
        }

    @Suppress("TooGenericExceptionCaught")
    override fun parsePczt(ur: UR): ByteArray =
        try {
            sdk.zcash.parsePczt(ur)
        } catch (e: Exception) {
            throw KeystoneSDKException(e)
        }
}
