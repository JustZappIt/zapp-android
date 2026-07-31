package co.electriccoin.zcash.ui.screen.reviewtransaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.security.PinAuthGate
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.GetExchangeRateUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveContactByAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.util.Ellipsize
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.design.util.styleAsAddress
import co.electriccoin.zcash.ui.preference.AuthMethod
import co.electriccoin.zcash.ui.preference.getAuthMethod
import co.electriccoin.zcash.ui.screen.addressbook.ADDRESS_MAX_LENGTH
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.util.Quintuple
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ReviewTransactionVM(
    getWalletAccounts: GetWalletAccountsUseCase,
    observeContactByAddress: ObserveContactByAddressUseCase,
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    observeProposal: ObserveProposalUseCase,
    private val cancelProposalFlow: CancelProposalFlowUseCase,
    private val getExchangeRate: GetExchangeRateUseCase,
    private val navigationRouter: NavigationRouter,
    private val submitProposal: SubmitProposalUseCase,
    private val biometricRepository: BiometricRepository,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
) : ViewModel() {
    /** Drives the PIN entry overlay shown before a transaction is submitted. */
    sealed class SendAuthState {
        object Idle : SendAuthState()

        object PinRequired : SendAuthState()

        object PinError : SendAuthState()

        /** Lockout in effect — input must be disabled and a countdown shown. */
        data class PinLocked(
            val secondsRemaining: Int
        ) : SendAuthState()
    }

    private val _sendAuthState = MutableStateFlow<SendAuthState>(SendAuthState.Idle)
    val sendAuthState: StateFlow<SendAuthState> = _sendAuthState.asStateFlow()
    private var sendLockoutTickerJob: Job? = null

    private fun startSendLockoutTicker(initialMs: Long) {
        sendLockoutTickerJob?.cancel()
        sendLockoutTickerJob =
            viewModelScope.launch {
                var remaining = initialMs
                while (remaining > 0) {
                    _sendAuthState.value = SendAuthState.PinLocked(((remaining + 999) / 1000).toInt())
                    delay(1_000)
                    remaining -= 1_000
                }
                _sendAuthState.value = SendAuthState.PinRequired
            }
    }

    private val isReceiverExpanded = MutableStateFlow(false)

    private val exchangeRate =
        flow {
            emit(getExchangeRate())
        }.shareIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            replay = 1
        )

    @Suppress("DestructuringDeclarationWithTooManyEntries")
    @OptIn(ExperimentalCoroutinesApi::class)
    val state =
        combine(
            observeSelectedWalletAccount.require(),
            observeProposal.filterSend(),
            isReceiverExpanded,
            exchangeRate,
            getWalletAccounts.observe()
        ) { wallet, zecSend, isReceiverExpanded, exchangeRate, accounts ->
            Quintuple(wallet, zecSend, isReceiverExpanded, exchangeRate, accounts)
        }.flatMapLatest { (selectedWallet, proposal, isReceiverExpanded, exchangeRate, accounts) ->
            observeContactByAddress(
                if (proposal is ExactOutputSwapTransactionProposal) {
                    proposal.quote.destinationAddress.address
                } else {
                    proposal.destination.address
                }
            ).map { addressBookContact ->
                when (proposal) {
                    is Zip321TransactionProposal -> {
                        createZip321State(
                            transactionProposal = proposal,
                            addressBookContact = addressBookContact,
                            selectedWallet = selectedWallet,
                            isReceiverExpanded = isReceiverExpanded,
                            exchangeRateState = exchangeRate
                        )
                    }

                    else -> {
                        createState(
                            transactionProposal = proposal,
                            addressBookContact = addressBookContact,
                            selectedWallet = selectedWallet,
                            exchangeRateState = exchangeRate,
                            accounts = accounts
                        )
                    }
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    private var onConfirmClickJob: Job? = null

    private fun createState(
        selectedWallet: WalletAccount,
        transactionProposal: SendTransactionProposal,
        addressBookContact: EnhancedABContact?,
        exchangeRateState: ExchangeRateState,
        accounts: List<WalletAccount>?
    ) = ReviewTransactionState(
        title =
            when (selectedWallet) {
                is KeystoneAccount -> stringRes(R.string.review_keystone_transaction_title)
                is ZashiAccount -> stringRes(R.string.send_stage_confirmation_title)
            },
        items =
            listOfNotNull(
                AmountState(
                    title = stringRes(R.string.send_confirmation_amount),
                    amount = transactionProposal.amount + transactionProposal.proposal.totalFeeRequired(),
                    exchangeRate = exchangeRateState,
                ),
                ReceiverState(
                    title = stringRes(R.string.send_confirmation_address),
                    name = addressBookContact?.name?.let { stringRes(it) },
                    address = stringResByAddress(transactionProposal.destination.address, Ellipsize.NONE)
                ),
                SenderState(
                    title = stringRes(R.string.send_confirmation_address_from),
                    icon = selectedWallet.icon,
                    name = selectedWallet.name
                ).takeIf { (accounts?.size ?: 0) > 1 },
                FinancialInfoState(
                    title = stringRes(R.string.send_amount_label),
                    amount = transactionProposal.amount
                ),
                FinancialInfoState(
                    title = stringRes(R.string.send_confirmation_fee),
                    amount = transactionProposal.proposal.totalFeeRequired()
                ),
                transactionProposal.memo
                    .takeIf { it.value.isNotEmpty() }
                    ?.let {
                        MessageState(
                            title = stringRes(R.string.send_memo_label),
                            message = stringRes(it.value)
                        )
                    }?.takeIf { transactionProposal.destination !is WalletAddress.Transparent },
                MessagePlaceholderState(
                    title = stringRes(R.string.send_memo_label),
                    message = stringRes(R.string.send_transparent_memo),
                    icon = R.drawable.ic_confirmation_message_info,
                ).takeIf { transactionProposal.destination is WalletAddress.Transparent },
            ),
        primaryButton =
            ButtonState(
                text =
                    when (selectedWallet) {
                        is KeystoneAccount -> stringRes(R.string.review_keystone_transaction_positive)
                        is ZashiAccount -> stringRes(R.string.send_confirmation_send_button)
                    },
                onClick = ::onConfirmClick
            ),
        onBack = ::onBack,
    )

    private fun createZip321State(
        transactionProposal: SendTransactionProposal,
        addressBookContact: EnhancedABContact?,
        selectedWallet: WalletAccount,
        isReceiverExpanded: Boolean,
        exchangeRateState: ExchangeRateState
    ) = ReviewTransactionState(
        title = stringRes(R.string.payment_request_title),
        items =
            listOfNotNull(
                AmountState(
                    title = null,
                    amount = transactionProposal.amount,
                    exchangeRate = exchangeRateState,
                ),
                SenderState(
                    title = stringRes(R.string.send_confirmation_address_from),
                    icon = selectedWallet.icon,
                    name = selectedWallet.name
                ),
                ReceiverExpandedState(
                    title = stringRes(R.string.payment_request_requested_by),
                    name = addressBookContact?.name?.let { stringRes(it) },
                    address =
                        stringResByAddress(
                            transactionProposal.destination.address,
                            if (isReceiverExpanded) Ellipsize.NONE else Ellipsize.END
                        ),
                    showButton =
                        ChipButtonState(
                            startIcon =
                                if (isReceiverExpanded) {
                                    co.electriccoin.zcash.ui.design.R.drawable.ic_chevron_up
                                } else {
                                    co.electriccoin.zcash.ui.design.R.drawable.ic_chevron_down
                                },
                            text = stringRes(R.string.payment_request_btn_show_address),
                            onClick = ::onExpandReceiverClick
                        ),
                    saveButton =
                        ChipButtonState(
                            startIcon = R.drawable.ic_user_plus,
                            text = stringRes(R.string.payment_request_btn_save_contact),
                            onClick = { onAddContactClick(transactionProposal.destination.address) }
                        ).takeIf { addressBookContact == null }
                ),
                transactionProposal.memo.takeIf { it.value.isNotEmpty() }?.let {
                    MessageState(
                        title = stringRes(R.string.payment_request_memo),
                        message = stringRes(it.value)
                    )
                },
                FinancialInfoState(
                    title = stringRes(R.string.payment_request_fee),
                    amount = transactionProposal.proposal.totalFeeRequired()
                )
            ),
        primaryButton =
            ButtonState(
                text =
                    when (selectedWallet) {
                        is KeystoneAccount -> stringRes(R.string.review_keystone_transaction_positive)
                        is ZashiAccount -> stringRes(R.string.payment_request_send_btn)
                    },
                onClick = ::onConfirmClick
            ),
        onBack = ::onBack,
    )

    private fun onExpandReceiverClick() = isReceiverExpanded.update { !it }

    private fun onBack() = viewModelScope.launch { cancelProposalFlow(clearSendForm = false) }

    private fun onConfirmClick() {
        if (onConfirmClickJob?.isActive == true) return
        onConfirmClickJob =
            viewModelScope.launch {
                val authMethod = standardPreferenceProvider().getAuthMethod()
                when (authMethod) {
                    AuthMethod.BIOMETRIC -> {
                        try {
                            biometricRepository.requestBiometrics(
                                BiometricRequest(
                                    message =
                                        stringRes(
                                            R.string.authentication_system_ui_subtitle,
                                            stringRes(R.string.authentication_use_case_send_funds)
                                        )
                                )
                            )
                            submitProposal()
                        } catch (_: BiometricsCancelledException) {
                            // User cancelled — stay on review screen
                        } catch (_: BiometricsFailureException) {
                            // Auth failed — stay on review screen
                        }
                    }

                    AuthMethod.PIN -> {
                        _sendAuthState.value = SendAuthState.PinRequired
                        // Submission deferred to onSendPinSubmitted
                    }

                    AuthMethod.NONE -> {
                        submitProposal()
                    }
                }
            }
    }

    /**
     * Called by the UI when the user submits a PIN on the send-auth overlay.
     * Verifies through [PinAuthGate] (shared global lockout) and, on success,
     * submits the transaction proposal.
     */
    fun onSendPinSubmitted(pin: String) {
        viewModelScope.launch {
            when (
                val result =
                    PinAuthGate.tryVerify(
                        pin,
                        encryptedPreferenceProvider,
                        standardPreferenceProvider,
                    )
            ) {
                PinAuthGate.Result.Success -> {
                    _sendAuthState.value = SendAuthState.Idle
                    submitProposal()
                }

                PinAuthGate.Result.Wrong -> {
                    _sendAuthState.value = SendAuthState.PinError
                    delay(1_500)
                    _sendAuthState.value = SendAuthState.PinRequired
                }

                is PinAuthGate.Result.Locked -> {
                    startSendLockoutTicker(result.msUntilUnlock)
                }
            }
        }
    }

    /** Called by the UI when the user cancels out of the send PIN overlay. */
    fun onSendAuthDismissed() {
        // Cancel the lockout ticker; otherwise its next tick re-shows the PIN overlay and traps the user.
        sendLockoutTickerJob?.cancel()
        _sendAuthState.value = SendAuthState.Idle
    }

    private fun onAddContactClick(address: String) = navigationRouter.forward(AddZashiABContactArgs(address))
}
