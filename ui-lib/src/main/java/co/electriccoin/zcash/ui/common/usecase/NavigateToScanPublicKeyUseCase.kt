package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.chat.scan.ChatScanPublicKeyArgs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

/**
 * Navigates to the public-key QR scanner and suspends until the user either scans a
 * valid 64-char hex public key or cancels. Mirrors [NavigateToScanGenericAddressUseCase]
 * but is scoped to the chat feature — no address validation, no amount.
 */
class NavigateToScanPublicKeyUseCase(
    private val navigationRouter: NavigationRouter
) {
    private val pipeline = MutableSharedFlow<ScanPublicKeyPipelineResult>()

    suspend operator fun invoke(): String? {
        val args = ChatScanPublicKeyArgs()
        navigationRouter.forward(args)
        val result = pipeline.first { it.args.requestId == args.requestId }
        return when (result) {
            is ScanPublicKeyPipelineResult.Cancelled -> null
            is ScanPublicKeyPipelineResult.Scanned -> result.publicKey
        }
    }

    suspend fun onScanned(publicKey: String, args: ChatScanPublicKeyArgs) {
        pipeline.emit(ScanPublicKeyPipelineResult.Scanned(publicKey, args))
        navigationRouter.back()
    }

    suspend fun onScanCancelled(args: ChatScanPublicKeyArgs) {
        pipeline.emit(ScanPublicKeyPipelineResult.Cancelled(args))
        navigationRouter.back()
    }
}

private sealed interface ScanPublicKeyPipelineResult {
    val args: ChatScanPublicKeyArgs

    data class Cancelled(
        override val args: ChatScanPublicKeyArgs
    ) : ScanPublicKeyPipelineResult

    data class Scanned(
        val publicKey: String,
        override val args: ChatScanPublicKeyArgs
    ) : ScanPublicKeyPipelineResult
}
