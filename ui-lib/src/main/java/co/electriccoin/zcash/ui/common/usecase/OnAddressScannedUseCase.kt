package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.ADDRESS_BOOK
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.HOMEPAGE
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.SEND
import co.electriccoin.zcash.ui.screen.unifiedsend.UnifiedSendArgs

class OnAddressScannedUseCase(
    private val navigationRouter: NavigationRouter,
    private val prefillSend: PrefillSendUseCase
) {
    operator fun invoke(
        address: String,
        addressType: AddressType,
        scanArgs: ScanArgs
    ) {
        require(addressType is AddressType.Valid)

        when (scanArgs.flow) {
            SEND -> {
                prefillSend.request(PrefillSendData.FromAddressScan(address = address))
                navigationRouter.back()
            }

            ADDRESS_BOOK -> {
                navigationRouter.replace(AddZashiABContactArgs(address))
            }

            HOMEPAGE -> {
                navigationRouter.replace(UnifiedSendArgs(recipientAddress = address))
            }
        }
    }
}
