// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.di

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.screen.grouplink.GroupInviteCoordinator
import co.electriccoin.zcash.ui.screen.grouplink.GroupInvitePreviewArgs
import co.electriccoin.zcash.ui.screen.grouplink.GroupInvitePreviewVM
import co.electriccoin.zcash.ui.screen.grouplink.GroupJoinRepository
import co.electriccoin.zcash.ui.screen.grouplink.GroupJoinRepositoryImpl
import co.electriccoin.zcash.ui.screen.grouplink.GroupLinkArgs
import co.electriccoin.zcash.ui.screen.grouplink.GroupLinkRepository
import co.electriccoin.zcash.ui.screen.grouplink.GroupLinkRepositoryImpl
import co.electriccoin.zcash.ui.screen.grouplink.GroupLinkVM
import co.electriccoin.zcash.ui.screen.grouplink.model.PendingGroupInviteStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module
import xyz.justzappit.zappmessaging.ZappMessagingSDK

/** Group invite links and member removal. Included by [zappMessagingModule]. */
val groupLinkModule =
    module {
        single { PendingGroupInviteStore(get<EncryptedPreferenceProvider>()) }
        singleOf(::GroupJoinRepositoryImpl) bind GroupJoinRepository::class
        singleOf(::GroupLinkRepositoryImpl) bind GroupLinkRepository::class
        single {
            GroupInviteCoordinator(
                store = get(),
                onboardingDone = onboardingDone(get()),
                identityReady = get<ZappMessagingSDK>().identity.map { it != null },
                isEnabled = BuildConfig.IS_GROUP_LINKS_ENABLED,
            )
        }
        viewModel { (args: GroupInvitePreviewArgs) ->
            GroupInvitePreviewVM(
                args = args,
                store = get(),
                groupLinks = get(),
                navigationRouter = get(),
            )
        }
        viewModel { (args: GroupLinkArgs) ->
            GroupLinkVM(
                args = args,
                groupLinks = get(),
                conversations = get(),
                copyToClipboard = get(),
                shareGroupLink = get(),
                navigationRouter = get(),
            )
        }
    }

private fun onboardingDone(preferences: StandardPreferenceProvider): Flow<Boolean> =
    flow {
        val provider = preferences()
        emitAll(
            combine(
                StandardPreferenceKeys.IS_WELCOME_DISMISSED.observe(provider),
                StandardPreferenceKeys.IS_ONBOARDING_COMPLETED.observe(provider),
            ) { welcomed, onboarded -> welcomed && onboarded },
        )
    }
