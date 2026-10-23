// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd.receive

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.CopyFeedback
import co.electriccoin.zcash.ui.common.atomicswap.AtomicSwapRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositLine
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositPhase
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositTerms
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDeposits
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdDepositsRepository
import co.electriccoin.zcash.ui.common.privateusd.PrivateUsdToken
import co.electriccoin.zcash.ui.common.privateusd.addressOrNull
import co.electriccoin.zcash.ui.common.privateusd.basisPoints
import co.electriccoin.zcash.ui.common.privateusd.tokenAmount
import co.electriccoin.zcash.ui.common.provider.RailgunMnemonicProvider
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.privateusd.PrivateUsdInfo
import co.electriccoin.zcash.ui.screen.privateusd.about
import co.electriccoin.zcash.ui.screen.privateusd.requireDeployment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import xyz.justzappit.offramp.atomicswap.RAILGUN_FEE
import xyz.justzappit.railgun.RailgunNetwork
import kotlin.time.Duration.Companion.seconds

class PrivateUsdReceiveVM(
    atomicSwapRepository: AtomicSwapRepository,
    private val depositsRepository: PrivateUsdDepositsRepository,
    private val railgunMnemonicProvider: RailgunMnemonicProvider,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val deployment = atomicSwapRepository.requireDeployment()
    private val copyFeedback = CopyFeedback(viewModelScope)

    /** The tab the user picked, if they did. */
    private val pickedTab = MutableStateFlow<PrivateUsdReceiveTab?>(null)

    // A proper name, the same in every language.
    private val network =
        stringRes(
            when (deployment.railgunNetwork) {
                RailgunNetwork.MAINNET -> R.string.private_usd_network_ethereum
                RailgunNetwork.SEPOLIA -> R.string.private_usd_network_sepolia
            }
        )

    // A 0zk address is shared to be paid: it says nothing of the balance.
    private val railgunAddress: Flow<AddressLoad> =
        railgunMnemonicProvider.walletChanges
            .map { railgunMnemonicProvider.addressOrNull()?.let { AddressLoad.Ready(it.value) } ?: AddressLoad.Failed }
            .onStart { emit(AddressLoad.Loading) }

    // A look at the deposit addresses as the USD tab opens, then another every PASS_INTERVAL while it shows.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val passes: Flow<Unit> =
        combine(pickedTab, depositsRepository.state, ::tabOf)
            .distinctUntilChanged()
            .transformLatest { tab ->
                emit(Unit)
                while (tab == PrivateUsdReceiveTab.USD) {
                    depositsRepository.refresh()
                    delay(PASS_INTERVAL)
                }
            }

    internal val state: StateFlow<PrivateUsdReceiveState> =
        combine(
            railgunAddress,
            depositsRepository.state,
            pickedTab,
            copyFeedback.copiedValue,
            passes,
        ) { address, deposits, picked, copied, _ ->
            createState(address, deposits, picked, copied)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(AddressLoad.Loading, depositsRepository.state.value, null, null),
        )

    private fun createState(
        railgunAddress: AddressLoad,
        deposits: PrivateUsdDeposits?,
        picked: PrivateUsdReceiveTab?,
        copied: String?,
    ) = PrivateUsdReceiveState(
        tab = tabOf(picked, deposits),
        usd = deposits?.let { usd(it, copied) },
        shielded =
            PrivateUsdReceiveShieldedState(
                notice = stringRes(R.string.private_usd_receive_shielded_only, network),
                address = address(railgunAddress, SHIELDED_ADDRESS, copied),
                note = stringRes(R.string.private_usd_receive_shielded_note),
            ),
        onSelectTab = { pickedTab.value = it },
        info = info(deposits),
        onBack = navigationRouter::back,
    )

    private fun usd(
        deposits: PrivateUsdDeposits,
        copied: String?
    ): PrivateUsdReceiveUsdState {
        val token = deposits.token
        val terms = deposits.terms
        // EIP-55, which exchanges' withdrawal forms take as it is.
        val address =
            when {
                deposits.address != null -> AddressLoad.Ready(deposits.address.checksumHex)
                deposits.isAddressFailed -> AddressLoad.Failed
                else -> AddressLoad.Loading
            }
        return PrivateUsdReceiveUsdState(
            notice = stringRes(R.string.private_usd_receive_usd_only, token.symbol, network),
            address = address(address, DEPOSIT_ADDRESS, copied),
            lines = deposits.lines.map { line(it, token, terms) },
            fees = terms?.let { fees(it, token) },
            feesStatus =
                when {
                    terms != null -> null
                    deposits.isTermsFailed -> stringRes(R.string.private_usd_receive_usd_fees_failed)
                    else -> stringRes(R.string.private_usd_receive_usd_fees_loading)
                },
            notes =
                listOf(
                    stringRes(R.string.private_usd_receive_usd_how),
                    stringRes(R.string.private_usd_receive_usd_available, deployment.screeningTime.about()),
                    stringRes(R.string.private_usd_receive_usd_public, network),
                ),
        )
    }

    private fun address(
        load: AddressLoad,
        texts: AddressTexts,
        copied: String?,
    ): PrivateUsdReceiveAddressState {
        val value = (load as? AddressLoad.Ready)?.value
        return PrivateUsdReceiveAddressState(
            value = value,
            status =
                when (load) {
                    AddressLoad.Loading -> stringRes(texts.loading)
                    AddressLoad.Failed -> stringRes(texts.failed)
                    is AddressLoad.Ready -> null
                },
            label = stringRes(texts.label),
            qrDescription = stringRes(texts.qr),
            copyDescription = stringRes(texts.copy),
            isCopied = value != null && value == copied,
            onCopy = { value?.let(::copy) },
        )
    }

    // Neither address says anything of the balance: each is there to be shared.
    private fun copy(address: String) {
        copyToClipboard(address, isSensitive = false)
        copyFeedback.mark(address)
    }

    // Public terms, so none of it hides with the balances.
    private fun fees(
        terms: PrivateUsdDepositTerms,
        token: PrivateUsdToken,
    ) = PrivateUsdReceiveFeesState(
        minimum = tokenAmount(terms.minValue, token),
        relayerFee =
            stringRes(
                R.string.private_usd_receive_usd_relayer_fee_value,
                tokenAmount(terms.fee, token),
                basisPoints(terms.feeBps),
            ),
        railgunFee = stringRes(R.string.private_usd_receive_usd_railgun_fee_value, basisPoints(RAILGUN_FEE)),
        example =
            terms.example?.let {
                stringRes(
                    R.string.private_usd_receive_usd_example,
                    tokenAmount(it.sent, token),
                    tokenAmount(it.arrives, token),
                )
            },
    )

    private fun line(
        line: PrivateUsdDepositLine,
        token: PrivateUsdToken,
        terms: PrivateUsdDepositTerms?,
    ): PrivateUsdReceiveLineState {
        val amount = tokenAmount(line.amount, token).asPrivacySensitive()
        val received = stringRes(R.string.private_usd_receive_line_received, amount)
        return when (line.phase) {
            PrivateUsdDepositPhase.RECEIVED -> {
                PrivateUsdReceiveLineState(received, PrivateUsdReceiveLineTone.ACTIVE)
            }

            PrivateUsdDepositPhase.UNDER_MINIMUM -> {
                PrivateUsdReceiveLineState(
                    terms?.let {
                        stringRes(
                            R.string.private_usd_receive_line_under_minimum,
                            amount,
                            tokenAmount(it.minValue, token),
                        )
                    } ?: received,
                    PrivateUsdReceiveLineTone.MUTED,
                )
            }

            PrivateUsdDepositPhase.WAITING -> {
                PrivateUsdReceiveLineState(
                    stringRes(R.string.private_usd_receive_line_waiting, amount),
                    PrivateUsdReceiveLineTone.MUTED,
                )
            }

            PrivateUsdDepositPhase.SHIELDING -> {
                PrivateUsdReceiveLineState(
                    stringRes(R.string.private_usd_receive_line_shielding, amount),
                    PrivateUsdReceiveLineTone.ACTIVE,
                )
            }

            PrivateUsdDepositPhase.SHIELDED -> {
                PrivateUsdReceiveLineState(
                    stringRes(R.string.private_usd_receive_line_shielded, amount, deployment.screeningTime.about()),
                    PrivateUsdReceiveLineTone.DONE,
                )
            }

            PrivateUsdDepositPhase.NOT_SHIELDING -> {
                PrivateUsdReceiveLineState(
                    stringRes(R.string.private_usd_receive_line_not_shielding, amount),
                    PrivateUsdReceiveLineTone.MUTED,
                )
            }
        }
    }

    private fun info(deposits: PrivateUsdDeposits?) =
        PrivateUsdInfo(
            title = stringRes(R.string.private_usd_receive_info_title),
            steps =
                listOfNotNull(
                    deposits?.let { stringRes(R.string.private_usd_receive_info_usd, it.token.symbol) },
                    stringRes(R.string.private_usd_receive_info_shielded),
                ),
            notes = listOf(stringRes(R.string.private_usd_info_step_screen, deployment.screeningTime.about())),
        )

    private companion object {
        val PASS_INTERVAL = 15.seconds

        val SHIELDED_ADDRESS =
            AddressTexts(
                label = R.string.private_usd_receive_address,
                loading = R.string.private_usd_receive_shielded_loading,
                failed = R.string.private_usd_receive_shielded_failed,
                qr = R.string.private_usd_receive_shielded_qr,
                copy = R.string.private_usd_copy_address,
            )

        val DEPOSIT_ADDRESS =
            AddressTexts(
                label = R.string.private_usd_receive_usd_address,
                loading = R.string.private_usd_receive_usd_loading,
                failed = R.string.private_usd_receive_usd_failed,
                qr = R.string.private_usd_receive_usd_qr,
                copy = R.string.private_usd_receive_usd_copy,
            )
    }
}

/** The tab the user picked, while there's a choice; until they pick, USD where deposits come in. */
private fun tabOf(
    picked: PrivateUsdReceiveTab?,
    deposits: PrivateUsdDeposits?,
): PrivateUsdReceiveTab =
    when {
        deposits == null -> PrivateUsdReceiveTab.SHIELDED
        else -> picked ?: PrivateUsdReceiveTab.USD
    }

private sealed interface AddressLoad {
    data object Loading : AddressLoad

    data object Failed : AddressLoad

    data class Ready(
        val value: String
    ) : AddressLoad
}

private class AddressTexts(
    @param:StringRes val label: Int,
    @param:StringRes val loading: Int,
    @param:StringRes val failed: Int,
    @param:StringRes val qr: Int,
    @param:StringRes val copy: Int,
)
