package co.electriccoin.zcash.ui.screen.signkeystonetransaction

import androidx.annotation.DrawableRes
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.util.StringResource

data class SignKeystoneTransactionState(
    val onBack: () -> Unit,
    val accountInfo: ZashiAccountInfoListItemState,
    val qrData: String?,
    val generateNextQrCode: () -> Unit,
    val shareButton: ButtonState?,
    val positiveButton: ButtonState,
    val negativeButton: ButtonState,
    // Null keeps the screen's own send-flow copy. The Ironwood migration overrides them so a
    // multi-round batch can say which round is on screen ("(1 of 2)"), which the fixed copy can't.
    val title: StringResource? = null,
    val subtitle: StringResource? = null,
) {
    fun toQrState(
        contentDescription: StringResource? = null,
        centerImageResId: Int? = null,
    ): QrState {
        requireNotNull(qrData) { "The QR code data needs to be set at this point" }
        return QrState(
            qrData = qrData,
            contentDescription = contentDescription,
            centerImage = centerImageResId
        )
    }
}

data class ZashiAccountInfoListItemState(
    @field:DrawableRes val icon: Int,
    val title: StringResource,
    val subtitle: StringResource,
)
