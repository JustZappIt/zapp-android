package co.electriccoin.zcash.ui.common.usecase

import kotlinx.coroutines.flow.Flow
import xyz.justzappit.zappmessaging.ZappMessagingSDK
import xyz.justzappit.zappmessaging.models.ZMIdentity

class ObserveChatIdentityUseCase(
    private val sdk: ZappMessagingSDK,
) {
    operator fun invoke(): Flow<ZMIdentity?> = sdk.identity
}
