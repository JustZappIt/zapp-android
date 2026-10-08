// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.DeviceAttestation
import xyz.justzappit.offramp.atomicswap.KeyAttestation
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import kotlin.io.encoding.Base64

/**
 * This install's key in the Android Keystore, attested by the phone's secure hardware up to Google's root, which the
 * token issuer named [issuer] counts each day's tokens against. It's made once, and again only once its chain has
 * expired: a key made because the issuer refused the last one, or had none left for it, would be a fresh allowance.
 */
class AtomicSwapAttestation internal constructor(
    private val issuer: String,
    private val keys: AttestationKeys,
    private val nowMillis: () -> Long,
) : DeviceAttestation {
    constructor(issuer: String) : this(issuer, AndroidAttestationKeys, System::currentTimeMillis)

    private val lock = Mutex()

    override suspend fun prepare() = keyed {}

    override suspend fun attest(
        challenge: String,
        blinded: List<ByteArray>
    ): KeyAttestation =
        keyed { chain ->
            val signature = keys.sign(ALIAS, KeyAttestation.signedMessage(base64Url.decode(challenge), blinded))
            KeyAttestation(challenge, chain.map { base64Url.encode(it.encoded) }, base64Url.encode(signature))
        }

    private suspend fun <T> keyed(use: (List<X509Certificate>) -> T): T =
        lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    use(chain())
                } catch (e: GeneralSecurityException) {
                    throw keyStoreFailed(e)
                } catch (e: ProviderException) {
                    throw keyStoreFailed(e)
                }
            }
        }

    private fun chain(): List<X509Certificate> {
        val kept = keys.chain(ALIAS)
        if (kept != null && kept.none { nowMillis() > it.notAfter.time }) return kept
        keys.generate(ALIAS, KeyAttestation.keyChallenge(issuer))
        return checkNotNull(keys.chain(ALIAS)) { "the keystore has no key just made" }
    }

    private companion object {
        const val ALIAS = "atomicswap_issuer_attestation"
        val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        fun keyStoreFailed(cause: Exception) =
            AtomicSwapBlockedException(AtomicSwapBlock.TOKENS_UNAVAILABLE, "this phone's keystore failed", cause)
    }
}

/** The secure hardware's side of the install's key: the Android Keystore, or a stand-in in tests. */
internal interface AttestationKeys {
    /** The chain of the key under [alias], leaf first, or none if there's no key. */
    fun chain(alias: String): List<X509Certificate>?

    /** Makes a key under [alias], in place of any there, attested for [challenge]. */
    fun generate(
        alias: String,
        challenge: ByteArray
    )

    /** The key's `SHA256withECDSA` signature of [message], DER. */
    fun sign(
        alias: String,
        message: ByteArray
    ): ByteArray
}

/**
 * EC P-256 signing keys, in StrongBox where the phone has one and in its TEE otherwise, used without the user unlocking
 * anything: fetches run in the background.
 */
private object AndroidAttestationKeys : AttestationKeys {
    private const val PROVIDER = "AndroidKeyStore"

    override fun chain(alias: String) = keyStore().getCertificateChain(alias)?.map { it as X509Certificate }

    override fun generate(
        alias: String,
        challenge: ByteArray
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !generatedInStrongBox(alias, challenge)) {
            generate(spec(alias, challenge).build())
        }
    }

    override fun sign(
        alias: String,
        message: ByteArray
    ): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyStore().getKey(alias, null) as PrivateKey)
            update(message)
            sign()
        }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun generatedInStrongBox(
        alias: String,
        challenge: ByteArray
    ) = try {
        generate(spec(alias, challenge).setIsStrongBoxBacked(true).build())
        true
    } catch (_: StrongBoxUnavailableException) {
        false
    }

    private fun spec(
        alias: String,
        challenge: ByteArray
    ) = KeyGenParameterSpec
        .Builder(alias, KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setAttestationChallenge(challenge)

    private fun generate(spec: KeyGenParameterSpec) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }
}
