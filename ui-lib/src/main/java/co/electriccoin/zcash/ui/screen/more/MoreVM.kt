package co.electriccoin.zcash.ui.screen.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenHowToVoteKeystoneStorageProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenHowToVoteStorageProvider
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToAddressBookUseCase
import co.electriccoin.zcash.ui.common.voting.VotingSettingsEntry
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.advancedsettings.AdvancedSettingsArgs
import co.electriccoin.zcash.ui.screen.exchangerate.settings.ExchangeRateSettingsArgs
import co.electriccoin.zcash.ui.screen.gift.GiftCardListArgs
import co.electriccoin.zcash.ui.screen.hotfix.enhancement.EnhancementHotfixArgs
import co.electriccoin.zcash.ui.screen.hotfix.ephemeral.EphemeralHotfixArgs
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MoreVM(
    private val getVersionInfo: GetVersionInfoProvider,
    private val navigationRouter: NavigationRouter,
    private val navigateToAddressBook: NavigateToAddressBookUseCase,
    private val hasSeenHowToVote: HasSeenHowToVoteStorageProvider,
    private val hasSeenHowToVoteKeystone: HasSeenHowToVoteKeystoneStorageProvider,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val votingSettingsEntry: VotingSettingsEntry,
) : ViewModel() {
    val state: StateFlow<MoreState> = MutableStateFlow(createState())

    private fun createState() =
        MoreState(
            version = stringRes(R.string.settings_version, getVersionInfo().versionName),
            onBack = ::onBack,
            items =
                listOfNotNull(
                    ListItemState(
                        title = stringRes(R.string.settings_address_book),
                        bigIcon = imageRes(R.drawable.ic_settings_address_book),
                        onClick = ::onAddressBookClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.advanced_settings_currency_conversion),
                        bigIcon = imageRes(R.drawable.ic_advanced_settings_currency_conversion),
                        onClick = ::onCurrencyConversionClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.settings_coinholderPolling),
                        bigIcon = imageRes(R.drawable.ic_settings_voting),
                        onClick = ::onVotingClick
                    ).takeIf { votingSettingsEntry.isEnabled },
                    ListItemState(
                        title = stringRes(R.string.settings_gift_cards),
                        bigIcon = imageRes(R.drawable.ic_settings_gift_cards),
                        onClick = ::onGiftCardsClick
                    ),
                    ListItemState(
                        title = stringRes(R.string.settings_advanced_settings),
                        bigIcon = imageRes(R.drawable.ic_advanced_settings),
                        onClick = ::onAdvancedSettingsClick
                    ),
                ).toImmutableList(),
            onVersionLongClick = ::onVersionLongClick,
            onVersionDoubleClick = ::onVersionDoubleClick
        )

    private fun onCurrencyConversionClick() = navigationRouter.forward(ExchangeRateSettingsArgs)

    private fun onVersionLongClick() = navigationRouter.forward(EphemeralHotfixArgs(address = null))

    private fun onVersionDoubleClick() = navigationRouter.forward(EnhancementHotfixArgs)

    private fun onBack() = navigationRouter.back()

    private fun onVotingClick() {
        viewModelScope.launch {
            val isKeystone = getSelectedWalletAccount() is KeystoneAccount
            val hasSeenHowToVoteForCurrentWallet =
                if (isKeystone) {
                    hasSeenHowToVoteKeystone.get()
                } else {
                    hasSeenHowToVote.get()
                }

            if (hasSeenHowToVoteForCurrentWallet) {
                votingSettingsEntry.navigateToCoinholderPolling()
            } else {
                votingSettingsEntry.navigateToHowToVote()
            }
        }
    }

    private fun onAdvancedSettingsClick() = navigationRouter.forward(AdvancedSettingsArgs)

    private fun onGiftCardsClick() = navigationRouter.forward(GiftCardListArgs)

    private fun onAddressBookClick() = viewModelScope.launch { navigateToAddressBook() }
}
