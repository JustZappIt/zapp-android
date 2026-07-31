package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.screen.chat.model.ChatContact
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository

class GetChatContactsUseCase(
    private val chatContactsRepository: ChatContactsRepository,
) {
    operator fun invoke(): List<ChatContact> = chatContactsRepository.contacts.value
}
