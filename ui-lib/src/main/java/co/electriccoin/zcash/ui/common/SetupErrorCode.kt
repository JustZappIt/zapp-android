package co.electriccoin.zcash.ui.common

import co.electriccoin.zcash.build.gitSha
import co.electriccoin.zcash.ui.BuildConfig
import xyz.justzappit.zappmessaging.models.ZMError

/**
 * Maps a setup failure to a short, non-sensitive code the user can relay to the Zapp team.
 * Deriving a chat identity is deterministic crypto over the wallet seed — what fails is the
 * IPC round-trip to the messaging worklet, so the codes describe that plumbing. Never includes
 * the seed phrase, keys, or display name.
 */
fun Throwable.toSetupErrorCode(): String =
    when (this) {
        is ZMError.NotInitialized -> "SDK_NOT_READY"
        is ZMError.IpcTimeout -> "IPC_TIMEOUT"
        is ZMError.IpcError -> "IPC_ERROR"
        is ZMError.WorkletError -> "WORKLET_ERROR"
        is ZMError.InvalidData -> "BAD_RESPONSE"
        is ZMError.InvalidSeedPhrase -> "INVALID_SEED"
        is ZMError.SecureStorageError -> "STORAGE_ERROR"
        else -> this::class.simpleName ?: "UNKNOWN"
    }

/**
 * A copy-pasteable diagnostic the user can send to support. Carries the failed operation, the
 * scrubbed [code], and the build identity (git SHA + flavor) so the failure maps to a commit.
 */
fun buildSetupDiagnostic(operation: String, code: String): String =
    buildString {
        appendLine("Zapp setup error")
        appendLine("when: $operation")
        appendLine("code: $code")
        append("build: ${gitSha.take(GIT_SHA_LEN)} ${BuildConfig.FLAVOR_network}/${BuildConfig.FLAVOR_distribution}")
    }

private const val GIT_SHA_LEN = 10
