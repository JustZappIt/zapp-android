package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.screen.chat.common.ChatBootstrap
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepository
import co.electriccoin.zcash.ui.screen.chat.repository.ChatContactsRepositoryImpl
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepository
import co.electriccoin.zcash.ui.screen.chat.repository.ChatConversationsRepositoryImpl
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import xyz.justzappit.zappmessaging.ZappMessagingSDK

val zappMessagingModule =
    module {
        singleOf(::ZappMessagingSDK)
        singleOf(::ChatContactsRepositoryImpl) bind ChatContactsRepository::class
        singleOf(::ChatConversationsRepositoryImpl) bind ChatConversationsRepository::class
        single { ChatBootstrap(androidApplication(), get(), get(), get(), get(), get(), get()) }
    }
