package co.electriccoin.zcash.ui.common.usecase

import kotlinx.coroutines.flow.merge
import xyz.justzappit.zappmessaging.ZappMessagingSDK

/**
 * Emits (mediaId, progress in 0..1) for both in-flight uploads and downloads, so the chat room can
 * render a determinate ring on a media bubble regardless of transfer direction.
 */
class ObserveChatMediaTransferProgressUseCase(
    private val sdk: ZappMessagingSDK,
) {
    operator fun invoke() = merge(sdk.mediaTransferProgress, sdk.mediaDownloadProgress)
}
