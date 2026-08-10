package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.TransactionPool
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zip318Kind
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.mapper.SwapSupportMapper
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_INPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.SwapStatus.EXPIRED
import co.electriccoin.zcash.ui.common.model.SwapStatus.FAILED
import co.electriccoin.zcash.ui.common.model.SwapStatus.INCOMPLETE_DEPOSIT
import co.electriccoin.zcash.ui.common.model.SwapStatus.PENDING
import co.electriccoin.zcash.ui.common.model.SwapStatus.PROCESSING
import co.electriccoin.zcash.ui.common.model.SwapStatus.REFUNDED
import co.electriccoin.zcash.ui.common.model.SwapStatus.SUCCESS
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.DetailedTransactionData
import co.electriccoin.zcash.ui.common.usecase.FlipTransactionBookmarkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetTransactionDetailByIdUseCase
import co.electriccoin.zcash.ui.common.usecase.MarkTxMemoAsReadUseCase
import co.electriccoin.zcash.ui.common.usecase.ResolveChatConversationForAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.SendTransactionAgainUseCase
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.common.wallet.toFiatString
import co.electriccoin.zcash.ui.common.wallet.zecFiatRate
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation.HIDDEN
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.loadingImageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.stringResByTransactionId
import co.electriccoin.zcash.ui.screen.chat.ChatRoomArgs
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.swap.detail.support.SwapSupportArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.info.ReceiveShieldedState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.ReceiveTransparentState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendShieldedState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendSwapState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendTransparentState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.ShieldingState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.TransactionDetailInfoState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.TransactionDetailMemoState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.TransactionDetailMemosState
import co.electriccoin.zcash.ui.screen.transactionnote.TransactionNote
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import co.electriccoin.zcash.ui.util.loggableNot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.MathContext

@Suppress("TooManyFunctions")
class TransactionDetailVM(
    getTransactionDetailById: GetTransactionDetailByIdUseCase,
    private val markTxMemoAsRead: MarkTxMemoAsReadUseCase,
    private val transactionDetailArgs: TransactionDetailArgs,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val sendTransactionAgain: SendTransactionAgainUseCase,
    private val flipTransactionBookmark: FlipTransactionBookmarkUseCase,
    private val mapper: CommonTransactionDetailMapper,
    private val getSwapMessage: SwapSupportMapper,
    private val resolveChatForAddress: ResolveChatConversationForAddressUseCase,
    exchangeRateRepository: ExchangeRateRepository,
    swapRepository: SwapRepository,
) : ViewModel() {
    val log = loggableNot("TransactionDetailVM")
    private val transaction =
        getTransactionDetailById
            .observe(transactionDetailArgs.transactionId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null
            )

    private val fiatRate =
        combine(
            exchangeRateRepository.state,
            swapRepository.assets.map { it.zecAsset?.usdPrice }.distinctUntilChanged(),
        ) { exchangeRate, zecUsdPrice ->
            zecFiatRate(exchangeRate, zecUsdPrice)
        }

    // Resolved once per recipient address, not inside the rate-driven state mapping: the
    // lookup does a full chat-contact IPC refresh and the result is static per transaction.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val viewChatButton =
        transaction
            .filterNotNull()
            .map { data -> data.recipient?.address?.takeIf { data.transaction is SendTransaction } }
            .distinctUntilChanged()
            .mapLatest { address -> address?.let { createViewChatButtonState(it) } }
            .onStart { emit(null) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state =
        combine(transaction.filterNotNull(), fiatRate, viewChatButton) { transaction, rate, viewChat ->
            Triple(transaction, rate, viewChat)
        }.mapLatest { (transaction, rate, viewChat) ->
            val info = createTransactionInfoState(transaction, rate)
            TransactionDetailState(
                onBack = ::onBack,
                info = info,
                header = createTransactionHeaderState(transaction, info, rate),
                primaryButton = createPrimaryButtonState(transaction),
                secondaryButton = createSecondaryButtonState(transaction),
                viewChatButton = viewChat,
                bookmarkButton =
                    IconButtonState(
                        icon =
                            if (transaction.metadata.isBookmarked) {
                                R.drawable.ic_transaction_detail_bookmark
                            } else {
                                R.drawable.ic_transaction_detail_no_bookmark
                            },
                        onClick = ::onBookmarkClick,
                        hapticFeedbackType =
                            if (transaction.metadata.isBookmarked) {
                                HapticFeedbackType.ToggleOff
                            } else {
                                HapticFeedbackType.ToggleOn
                            }
                    ),
                errorFooter = createErrorFooter(transaction),
                infoFooter =
                    stringRes(R.string.transaction_detail_info_pending)
                        .takeIf { transaction.swap?.status?.status == PENDING }
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createEmptyState()
        )

    init {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val transaction = transaction.filterNotNull().first()
                if (transaction.transaction.memoCount > 0) {
                    markTxMemoAsRead(transactionDetailArgs.transactionId)
                }
            }
        }
    }

    private fun createEmptyState(): TransactionDetailState =
        TransactionDetailState(
            onBack = ::onBack,
            bookmarkButton = null,
            header =
                TransactionDetailHeaderState(
                    title = null,
                    amount = null,
                    icons =
                        listOf(
                            loadingImageRes(),
                            loadingImageRes(),
                            loadingImageRes(),
                        )
                ),
            info = null,
            errorFooter = null,
            infoFooter = null,
            primaryButton = null,
            secondaryButton = null,
        )

    private fun onAddOrEditNoteClick() {
        navigationRouter.forward(TransactionNote(transactionDetailArgs.transactionId))
    }

    @Suppress("CyclomaticComplexMethod")
    private fun createTransactionInfoState(
        transaction: DetailedTransactionData,
        rate: ZecFiatRate?
    ): TransactionDetailInfoState {
        log("createTransactionInfoState ${transaction.transaction}")
        return when (transaction.transaction) {
            is SendTransaction -> {
                when {
                    transaction.swap != null -> {
                        val recipient =
                            transaction.swap.status
                                ?.quote
                                ?.destinationAddress
                                ?.address
                        SendSwapState(
                            status = transaction.swap.status?.status,
                            message = getSwapMessage.getMessage(transaction.swap.status),
                            quoteHeader =
                                mapper.createTransactionDetailQuoteHeaderState(
                                    swap = transaction.swap.status,
                                    originAsset = transaction.swap.originAsset,
                                    destinationAsset = transaction.swap.destinationAsset
                                ),
                            depositAddress =
                                stringResByAddress(
                                    value = transaction.recipient?.address.orEmpty()
                                ),
                            totalFees =
                                transaction.metadata.swapMetadata
                                    ?.totalFees
                                    ?.let { stringRes(it) },
                            exchangeRate =
                                transaction.swap.destinationAsset?.tokenTicker?.let { destTicker ->
                                    transaction.swap.status
                                        ?.takeIf { it.amountInFormatted.signum() > 0 }
                                        ?.let { status ->
                                            val rate =
                                                status.amountOutFormatted
                                                    .divide(status.amountInFormatted, MathContext.DECIMAL128)
                                            stringRes(
                                                R.string.transaction_detail_info_exchange_rate_value,
                                                CURRENCY_TICKER,
                                                stringResByCurrencyNumber(
                                                    amount = rate,
                                                    ticker = destTicker,
                                                    maxDecimals = EXCHANGE_RATE_MAX_DECIMALS,
                                                ),
                                            )
                                        }
                                },
                            totalFeesUsd =
                                transaction.metadata.swapMetadata
                                    ?.totalFeesUsd
                                    ?.let { stringResByDynamicCurrencyNumber(it, FiatCurrency.USD.symbol) },
                            recipientAddress = recipient?.let { stringResByAddress(it) },
                            transactionId =
                                stringResByTransactionId(
                                    value = transaction.transaction.id.txIdString(),
                                    abbreviated = true
                                ),
                            refundedAmount =
                                transaction.swap.status
                                    ?.refundedFormatted
                                    ?.let {
                                        stringResByCurrencyNumber(amount = it, ticker = CURRENCY_TICKER)
                                    }?.takeIf {
                                        transaction.swap.status.status == REFUNDED
                                    },
                            onTransactionIdClick = {
                                onCopyToClipboard(transaction.transaction.id.txIdString())
                            },
                            onDepositAddressClick = {
                                onCopyToClipboard(transaction.recipient?.address.orEmpty())
                            },
                            onRecipientAddressClick =
                                if (recipient == null) {
                                    null
                                } else {
                                    { onCopyToClipboard(recipient) }
                                },
                            maxSlippage =
                                transaction.swap.status?.maxSlippage?.let {
                                    stringResByNumber(it, 0) + stringRes("%")
                                },
                            note = transaction.metadata.note?.let { stringRes(it) },
                            isSlippageRealized = transaction.swap.status?.isSlippageRealized == true,
                            isPending = isPending(transaction),
                            completedTimestamp = createTimestampStringRes(transaction),
                        )
                    }

                    transaction.recipient is WalletAddress.Transparent -> {
                        SendTransparentState(
                            contact = transaction.contact?.let { stringRes(it.name) },
                            address = stringRes(transaction.recipient.address),
                            addressAbbreviated = stringResByAddress(transaction.recipient.address),
                            transactionId =
                                stringResByTransactionId(
                                    value = transaction.transaction.id.txIdString(),
                                    abbreviated = true
                                ),
                            onTransactionIdClick = {
                                onCopyToClipboard(transaction.transaction.id.txIdString())
                            },
                            onTransactionAddressClick = { onCopyToClipboard(transaction.recipient.address) },
                            fee = createFeeStringRes(transaction),
                            feeFiat = createFeeFiat(transaction, rate),
                            completedTimestamp = createTimestampStringRes(transaction),
                            isPending = isPending(transaction),
                            note = transaction.metadata.note?.let { stringRes(it) },
                        )
                    }

                    else -> {
                        SendShieldedState(
                            contact = transaction.contact?.let { stringRes(it.name) },
                            address =
                                stringResByAddress(
                                    value = transaction.recipient?.address.orEmpty()
                                ),
                            transactionId =
                                stringResByTransactionId(
                                    value = transaction.transaction.id.txIdString(),
                                    abbreviated = true
                                ),
                            onTransactionIdClick = {
                                onCopyToClipboard(transaction.transaction.id.txIdString())
                            },
                            onTransactionAddressClick = {
                                onCopyToClipboard(transaction.recipient?.address.orEmpty())
                            },
                            fee = createFeeStringRes(transaction),
                            feeFiat = createFeeFiat(transaction, rate),
                            completedTimestamp = createTimestampStringRes(transaction),
                            memo =
                                TransactionDetailMemosState(
                                    transaction.memos.orEmpty().map { memo ->
                                        TransactionDetailMemoState(
                                            content = stringRes(memo),
                                            onClick = { onCopyToClipboard(memo) }
                                        )
                                    }
                                ),
                            note = transaction.metadata.note?.let { stringRes(it) },
                            isPending = isPending(transaction)
                        )
                    }
                }
            }

            is ReceiveTransaction -> {
                if (transaction.transaction.transactionOutputs.all { it.pool == TransactionPool.TRANSPARENT }) {
                    ReceiveTransparentState(
                        transactionId =
                            stringResByTransactionId(
                                value = transaction.transaction.id.txIdString(),
                                abbreviated = true
                            ),
                        onTransactionIdClick = {
                            onCopyToClipboard(transaction.transaction.id.txIdString())
                        },
                        completedTimestamp = createTimestampStringRes(transaction),
                        note = transaction.metadata.note?.let { stringRes(it) },
                        isPending = isPending(transaction)
                    )
                } else {
                    ReceiveShieldedState(
                        transactionId =
                            stringResByTransactionId(
                                value = transaction.transaction.id.txIdString(),
                                abbreviated = true
                            ),
                        onTransactionIdClick = {
                            onCopyToClipboard(transaction.transaction.id.txIdString())
                        },
                        completedTimestamp = createTimestampStringRes(transaction),
                        memo =
                            TransactionDetailMemosState(
                                transaction.memos?.map { memo ->
                                    TransactionDetailMemoState(
                                        content = stringRes(memo),
                                        onClick = { onCopyToClipboard(memo) }
                                    )
                                }
                            ),
                        note = transaction.metadata.note?.let { stringRes(it) },
                        isPending = isPending(transaction)
                    )
                }
            }

            is ShieldTransaction -> {
                ShieldingState(
                    transactionId =
                        stringResByTransactionId(
                            value = transaction.transaction.id.txIdString(),
                            abbreviated = true
                        ),
                    onTransactionIdClick = {
                        onCopyToClipboard(transaction.transaction.id.txIdString())
                    },
                    completedTimestamp = createTimestampStringRes(transaction),
                    fee = createFeeStringRes(transaction),
                    feeFiat = createFeeFiat(transaction, rate),
                    note = transaction.metadata.note?.let { stringRes(it) },
                    isPending = isPending(transaction)
                )
            }
        }
    }

    private fun createFeeStringRes(data: DetailedTransactionData): StringResource {
        val feePaid =
            data.transaction.fee.takeIf { data.transaction !is ReceiveTransaction }
                ?: return stringRes(R.string.transaction_detail_fee_minimal, CURRENCY_TICKER)

        return stringRes(feePaid)
    }

    private fun createFeeFiat(data: DetailedTransactionData, rate: ZecFiatRate?): StringResource? {
        rate ?: return null
        val feePaid =
            data.transaction.fee?.takeIf { data.transaction !is ReceiveTransaction && it.value > 0L }
        return feePaid?.let { rate.toFiatString(it) }
    }

    private fun createTimestampStringRes(data: DetailedTransactionData) =
        mapper.createTransactionDetailTimestamp(data.transaction.timestamp)

    private fun isPending(data: DetailedTransactionData) = data.transaction.timestamp == null

    private fun onCopyToClipboard(text: String) {
        copyToClipboard(
            value = text
        )
    }

    private fun createErrorFooter(data: DetailedTransactionData): ErrorFooter? =
        mapper.createTransactionDetailErrorFooter(data.swap?.error)

    private fun createPrimaryButtonState(data: DetailedTransactionData): ButtonState? {
        val supportButton =
            getSwapMessage.getButton(data.swap?.status) {
                onContactSupport(it)
            }
        return when {
            supportButton != null -> {
                supportButton
            }

            data.swap?.error != null && data.swap.status == null -> {
                mapper.createTransactionDetailErrorButtonState(
                    error = data.swap.error,
                    reloadHandle = data.reloadHandle
                )
            }

            data.swap != null -> {
                null
            }

            data.contact == null -> {
                if (data.transaction is SendTransaction) {
                    ButtonState(
                        text = stringRes(R.string.transactionHistory_saveAddress),
                        onClick = { onSaveAddressClick(data) }
                    )
                } else {
                    null
                }
            }

            else -> {
                if (data.transaction is SendTransaction) {
                    ButtonState(
                        text = stringRes(R.string.transactionHistory_sendAgain),
                        onClick = { onSendAgainClick(data) }
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun onContactSupport(depositAddress: String) {
        navigationRouter.forward(SwapSupportArgs(depositAddress))
    }

    // "View chat" links a send back to the 1:1 conversation with that recipient, when the recipient
    // address belongs to a chat contact we already have a direct conversation with.
    private suspend fun createViewChatButtonState(address: String): ButtonState? {
        val conversationId = resolveChatForAddress(address) ?: return null
        return ButtonState(
            text = stringRes(R.string.transaction_detail_view_chat),
            onClick = { navigationRouter.forward(ChatRoomArgs(conversationId)) }
        )
    }

    private fun createSecondaryButtonState(transaction: DetailedTransactionData): ButtonState? {
        fun createAddNoteButtonState() =
            ButtonState(
                text =
                    if (transaction.metadata.note != null) {
                        stringRes(R.string.transaction_detail_edit_note)
                    } else {
                        stringRes(R.string.annotation_addArticle)
                    },
                onClick = ::onAddOrEditNoteClick
            )

        return when {
            transaction.swap != null &&
                transaction.swap.error == null &&
                transaction.swap.status?.status == SUCCESS
            -> createAddNoteButtonState()

            transaction.swap != null -> null

            else -> createAddNoteButtonState()
        }
    }

    private fun onSaveAddressClick(transaction: DetailedTransactionData) {
        transaction.recipient?.let {
            navigationRouter.forward(AddZashiABContactArgs(it.address))
        }
    }

    private fun onSendAgainClick(transaction: DetailedTransactionData) {
        sendTransactionAgain(transaction)
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun createTransactionHeaderState(
        data: DetailedTransactionData,
        info: TransactionDetailInfoState,
        rate: ZecFiatRate?
    ): TransactionDetailHeaderState =
        TransactionDetailHeaderState(
            title =
                when (val transaction = data.transaction) {
                    is ReceiveTransaction.Success -> {
                        stringRes(R.string.transaction_received)
                    }

                    is ReceiveTransaction.Pending -> {
                        stringRes(R.string.transaction_receiving)
                    }

                    is ReceiveTransaction.Failed -> {
                        stringRes(R.string.transaction_history_receiving_failed)
                    }

                    is ShieldTransaction.Success -> {
                        stringRes(R.string.transaction_shieldedFunds)
                    }

                    is ShieldTransaction.Pending -> {
                        stringRes(R.string.transaction_shieldingFunds)
                    }

                    is ShieldTransaction.Failed -> {
                        stringRes(R.string.transaction_failedShieldedFunds)
                    }

                    is SendTransaction -> {
                        if (data.metadata.swapMetadata == null) {
                            when (transaction.overview.zip318Kind) {
                                Zip318Kind.PREPARATION -> {
                                    when (transaction) {
                                        is SendTransaction.Success -> stringRes(R.string.transaction_noteSplit)
                                        is SendTransaction.Pending -> stringRes(R.string.transaction_noteSplitting)
                                        is SendTransaction.Failed -> stringRes(R.string.transaction_noteSplitFailed)
                                    }
                                }

                                Zip318Kind.TRANSFER -> {
                                    when (transaction) {
                                        is SendTransaction.Success -> stringRes(R.string.transaction_migrated)
                                        is SendTransaction.Pending -> stringRes(R.string.transaction_migrating)
                                        is SendTransaction.Failed -> stringRes(R.string.transaction_migrationFailed)
                                    }
                                }

                                Zip318Kind.NOT_CLASSIFIED, Zip318Kind.NONCONFORMING -> {
                                    when (transaction) {
                                        is SendTransaction.Success -> {
                                            stringRes(R.string.transaction_sent)
                                        }

                                        is SendTransaction.Pending -> {
                                            stringRes(R.string.transaction_sending)
                                        }

                                        is SendTransaction.Failed -> {
                                            stringRes(R.string.transaction_history_sending_failed)
                                        }
                                    }
                                }
                            }
                        } else {
                            if (transaction is SendTransaction.Failed) {
                                when (data.metadata.swapMetadata.mode) {
                                    EXACT_INPUT -> stringRes(R.string.swapStatus_swapFailed)
                                    EXACT_OUTPUT -> stringRes(R.string.swapStatus_paymentFailed)
                                    FLEX_INPUT -> throw UnsupportedOperationException("FLEX_INPUT not supported")
                                }
                            } else {
                                when (data.metadata.swapMetadata.mode) {
                                    EXACT_INPUT -> {
                                        when (data.metadata.swapMetadata.status) {
                                            PROCESSING,
                                            PENDING -> stringRes(R.string.swapStatus_swapping)

                                            INCOMPLETE_DEPOSIT -> stringRes(R.string.swapStatus_swapIncomplete)

                                            SUCCESS -> stringRes(R.string.swapStatus_swapped)

                                            REFUNDED -> stringRes(R.string.swapStatus_swapRefunded)

                                            FAILED -> stringRes(R.string.swapStatus_swapFailed)

                                            EXPIRED -> stringRes(R.string.swapAndPay_expiredTitle)
                                        }
                                    }

                                    EXACT_OUTPUT -> {
                                        when (data.metadata.swapMetadata.status) {
                                            PROCESSING,
                                            PENDING -> stringRes(R.string.swapStatus_paying)

                                            INCOMPLETE_DEPOSIT -> stringRes(R.string.swapStatus_paymentIncomplete)

                                            SUCCESS -> stringRes(R.string.swapStatus_paid)

                                            REFUNDED -> stringRes(R.string.swapStatus_paymentRefunded)

                                            FAILED -> stringRes(R.string.swapStatus_paymentFailed)

                                            EXPIRED -> stringRes(R.string.swapStatus_paymentExpired)
                                        }
                                    }

                                    FLEX_INPUT -> {
                                        throw UnsupportedOperationException("FLEX_INPUT not supported")
                                    }
                                }
                            }
                        }
                    }
                },
            amount =
                stringRes(data.transaction.amount, HIDDEN),
            icons =
                when (info) {
                    is ReceiveShieldedState,
                    is ReceiveTransparentState -> {
                        listOf(
                            imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                            imageRes(R.drawable.ic_transaction_received)
                        )
                    }

                    is SendSwapState -> {
                        listOf(
                            data.metadata.swapMetadata
                                ?.origin
                                ?.tokenIcon ?: loadingImageRes(),
                            when (data.metadata.swapMetadata?.mode) {
                                EXACT_INPUT -> imageRes(R.drawable.ic_transaction_sent)
                                EXACT_OUTPUT -> imageRes(R.drawable.ic_transaction_paid)
                                FLEX_INPUT -> throw UnsupportedOperationException("FLEX_INPUT not supported")
                                null -> imageRes(R.drawable.ic_transaction_sent)
                            },
                            data.metadata.swapMetadata
                                ?.destination
                                ?.tokenIcon ?: loadingImageRes()
                        )
                    }

                    is SendShieldedState,
                    is SendTransparentState -> {
                        listOf(
                            imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                            imageRes(
                                when (data.transaction.overview.zip318Kind) {
                                    Zip318Kind.PREPARATION,
                                    Zip318Kind.TRANSFER -> R.drawable.ic_transaction_migration

                                    Zip318Kind.NOT_CLASSIFIED,
                                    Zip318Kind.NONCONFORMING -> R.drawable.ic_transaction_sent
                                }
                            )
                        )
                    }

                    is ShieldingState -> {
                        listOf(
                            imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                            imageRes(R.drawable.ic_transaction_shielded),
                        )
                    }
                },
            fiatAmount = rate?.toFiatString(data.transaction.amount),
        )

    private fun onBack() = navigationRouter.back()

    private fun onBookmarkClick() =
        viewModelScope.launch {
            flipTransactionBookmark(transactionDetailArgs.transactionId)
        }

    private companion object {
        const val EXCHANGE_RATE_MAX_DECIMALS = 4
    }
}
