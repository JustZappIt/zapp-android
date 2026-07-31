package co.electriccoin.zcash.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import org.koin.android.ext.android.inject

class BiometricActivity : FragmentActivity() {
    private val biometricRepository by inject<BiometricRepository>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestCode = intent.getStringExtra(EXTRA_REQUEST_CODE).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()

        val biometricPrompt =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(application),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                        super.onAuthenticationError(errorCode, errString)
                        biometricRepository.onBiometricResult(errorCode.toBiometricResult(requestCode))
                        finish()
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        biometricRepository.onBiometricResult(BiometricResult.Success(requestCode))
                        finish()
                    }
                }
            )

        val promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(
                    getString(R.string.authentication_system_ui_title, getString(R.string.app_name))
                ).setSubtitle(subtitle)
                .setAllowedAuthenticators(biometricRepository.allowedAuthenticators)
                .build()

        // Threat model: this is a boolean auth gate, not a key-bound one. No BiometricPrompt.CryptoObject
        // is passed, so a successful prompt only proves a biometric/credential event happened — it does not
        // unwrap a Keystore key, and the success callback can be forged on a rooted/instrumented device.
        // Upstream-inherited (upstream zodl-android has the identical flow); the fork additionally gates the
        // chat seed reveal behind this gate, so the practical stakes are higher here. Binding the highest-
        // value reveals to a Keystore CryptoObject (setUserAuthenticationRequired + invalidate-on-enroll)
        // is deferred to upstream #7.
        // TODO [#7]: Consider integrating with the keystore to unlock cryptographic operations
        // TODO [#7]: https://github.com/Electric-Coin-Company/zashi/issues/7
        biometricPrompt.authenticate(promptInfo)
    }

    companion object {
        private const val EXTRA_REQUEST_CODE = "EXTRA_REQUEST_CODE"
        private const val EXTRA_SUBTITLE = "EXTRA_SUBTITLE"

        fun createIntent(
            context: Context,
            requestCode: String,
            subtitle: String
        ) = Intent(context, BiometricActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_REQUEST_CODE, requestCode)
            putExtra(EXTRA_SUBTITLE, subtitle)
        }
    }
}

internal fun Int.toBiometricResult(requestCode: String): BiometricResult =
    when (this) {
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED -> BiometricResult.Cancelled(requestCode)

        else -> BiometricResult.Failure(requestCode)
    }
