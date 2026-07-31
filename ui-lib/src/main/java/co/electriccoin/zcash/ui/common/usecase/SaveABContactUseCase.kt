package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository

class SaveABContactUseCase(
    private val addressBookRepository: AddressBookRepository,
    private val navigationRouter: NavigationRouter,
) {
    operator fun invoke(
        name: String,
        address: String,
        chain: String?,
        walletAddresses: Map<String, String> = emptyMap(),
    ) {
        addressBookRepository.saveContact(
            name = name,
            address = address,
            chain = chain,
            walletAddresses = walletAddresses,
        )
        navigationRouter.back()
    }
}
