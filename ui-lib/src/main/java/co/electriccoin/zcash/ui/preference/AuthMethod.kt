package co.electriccoin.zcash.ui.preference

import co.electriccoin.zcash.preference.api.PreferenceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AuthMethod(
    val persistedValue: String
) {
    NONE("none"),
    BIOMETRIC("biometric"),
    PIN("pin"),
    ;

    val isConfigured: Boolean
        get() = this != NONE

    companion object {
        fun fromPersistedValue(value: String): AuthMethod = entries.firstOrNull { it.persistedValue == value } ?: NONE
    }
}

suspend fun PreferenceProvider.getAuthMethod(): AuthMethod =
    AuthMethod.fromPersistedValue(StandardPreferenceKeys.AUTH_METHOD.getValue(this))

fun PreferenceProvider.observeAuthMethod(): Flow<AuthMethod> =
    StandardPreferenceKeys.AUTH_METHOD.observe(this).map(AuthMethod::fromPersistedValue)

suspend fun PreferenceProvider.putAuthMethod(method: AuthMethod) {
    StandardPreferenceKeys.AUTH_METHOD.putValue(this, method.persistedValue)
}
