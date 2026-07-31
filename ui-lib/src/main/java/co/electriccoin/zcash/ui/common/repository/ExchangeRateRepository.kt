package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.ObserveFiatCurrencyResult
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateDataSource
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateUnavailable
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateError
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.common.wallet.RefreshLock
import co.electriccoin.zcash.ui.common.wallet.StaleLock
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

interface ExchangeRateRepository {
    val state: StateFlow<ExchangeRateState>

    fun refreshExchangeRateUsd()
}

class ExchangeRateRepositoryImpl(
    isExchangeRateEnabledStorageProvider: IsExchangeRateEnabledStorageProvider,
    preferredFiatProvider: PreferredFiatProvider,
    private val exchangeRateDataSource: ExchangeRateDataSource,
    private val synchronizerProvider: SynchronizerProvider,
) : ExchangeRateRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val refreshPipeline = MutableSharedFlow<Unit>()

    private val isExchangeRateOptedIn =
        isExchangeRateEnabledStorageProvider
            .observe()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null
            )

    private val preferredFiat =
        preferredFiatProvider
            .observe()
            .map { it ?: FiatCurrency.USD }
            .distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = FiatCurrency.USD
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val exchangeRateUsdInternal =
        combine(isExchangeRateOptedIn, preferredFiat) { optedIn, fiat -> optedIn to fiat }
            .flatMapLatest { (optedIn, fiat) ->
                if (optedIn == true) {
                    channelFlow {
                        var cache = ExchangeRateResult(ObserveFiatCurrencyResult(isLoading = true))
                        launch {
                            refreshPipeline
                                .onStart { emit(Unit) }
                                .flatMapLatest {
                                    flow {
                                        emit(cache.copy(observed = cache.observed.copy(isLoading = true)))
                                        emit(
                                            ExchangeRateResult(
                                                observed =
                                                    cache.observed.copy(
                                                        isLoading = false,
                                                        currencyConversion =
                                                            exchangeRateDataSource.getExchangeRate(fiat)
                                                    )
                                            )
                                        )
                                    }.catch { error ->
                                        if (shouldFallBackToSynchronizerRoute(VersionInfo.IS_CMC_AVAILABLE, fiat)) {
                                            synchronizerProvider.getSynchronizer().refreshExchangeRateUsd()
                                            emitAll(
                                                exchangeRateDataSource
                                                    .observeSynchronizerRoute()
                                                    .map { ExchangeRateResult(observed = it) }
                                            )
                                        } else {
                                            emit(
                                                cache.copy(
                                                    observed = cache.observed.copy(isLoading = false),
                                                    error = classifyExchangeRateError(error)
                                                )
                                            )
                                        }
                                    }
                                }.collect {
                                    cache = it
                                    send(cache)
                                }
                        }
                        awaitClose()
                    }
                } else {
                    flowOf(ExchangeRateResult())
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(USD_EXCHANGE_REFRESH_LOCK_THRESHOLD),
                initialValue = ExchangeRateResult(ObserveFiatCurrencyResult(isLoading = true))
            )

    private val usdExchangeRateTimestamp =
        exchangeRateUsdInternal
            .map {
                it.observed.currencyConversion?.timestamp
            }.distinctUntilChanged()

    private val refreshExchangeRateUsdLock =
        RefreshLock(
            timestampToObserve = usdExchangeRateTimestamp,
            lockDuration = USD_EXCHANGE_REFRESH_LOCK_THRESHOLD
        )

    private val staleExchangeRateUsdLock =
        StaleLock(
            timestampToObserve = usdExchangeRateTimestamp,
            lockDuration = USD_EXCHANGE_STALE_LOCK_THRESHOLD,
            onRefresh = { refreshExchangeRateUsdInternal().join() }
        )

    override val state: StateFlow<ExchangeRateState> =
        combine(
            isExchangeRateOptedIn,
            exchangeRateUsdInternal,
            staleExchangeRateUsdLock.state,
            refreshExchangeRateUsdLock.state,
            preferredFiat,
        ) { isOptedIn, exchangeRate, isStale, isRefreshEnabled, expectedCurrency ->
            createState(isOptedIn, exchangeRate, isStale, isRefreshEnabled, expectedCurrency)
        }.distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5.seconds),
                initialValue =
                    createState(
                        isOptedIn = isExchangeRateOptedIn.value,
                        exchangeRate = exchangeRateUsdInternal.value,
                        isStale = false,
                        isRefreshEnabled = false,
                        expectedCurrency = preferredFiat.value,
                    )
            )

    private fun createState(
        isOptedIn: Boolean?,
        exchangeRate: ExchangeRateResult,
        isStale: Boolean,
        isRefreshEnabled: Boolean,
        expectedCurrency: FiatCurrency,
    ): ExchangeRateState =
        when (isOptedIn) {
            true -> {
                ExchangeRateState.Data(
                    isLoading = exchangeRate.observed.isLoading,
                    isStale = isStale,
                    isRefreshEnabled = isRefreshEnabled,
                    currencyConversion = exchangeRate.observed.currencyConversion,
                    expectedCurrency = expectedCurrency,
                    error = exchangeRate.error,
                    onRefresh = ::refreshExchangeRateUsd
                )
            }

            false -> {
                ExchangeRateState.OptedOut
            }

            null -> {
                ExchangeRateState.OptIn
            }
        }

    override fun refreshExchangeRateUsd() {
        refreshExchangeRateUsdInternal()
    }

    private fun refreshExchangeRateUsdInternal() =
        scope.launch {
            val value = state.value
            if (value is ExchangeRateState.Data && value.isRefreshEnabled && !value.isLoading) {
                refreshPipeline.emit(Unit)
            }
        }
}

private val USD_EXCHANGE_REFRESH_LOCK_THRESHOLD = 2.minutes
private val USD_EXCHANGE_STALE_LOCK_THRESHOLD = 15.minutes

internal fun shouldFallBackToSynchronizerRoute(
    isCmcAvailable: Boolean,
    fiat: FiatCurrency,
): Boolean = !isCmcAvailable || fiat == FiatCurrency.USD

internal fun classifyExchangeRateError(error: Throwable): ExchangeRateError? =
    when (error) {
        is ExchangeRateUnavailable -> ExchangeRateError.CMC_EXCHANGE_RATE_UNAVAILABLE
        is ResponseException -> ExchangeRateError.CMC_SERVER_CONNECTION_ERROR
        is UnknownHostException -> ExchangeRateError.CMC_SERVER_CONNECTION_ERROR
        is ContentConvertException -> ExchangeRateError.CMC_SERVER_CONNECTION_ERROR
        is NoTransformationFoundException -> ExchangeRateError.CMC_SERVER_CONNECTION_ERROR
        else -> null
    }

private data class ExchangeRateResult(
    val observed: ObserveFiatCurrencyResult = ObserveFiatCurrencyResult(),
    val error: ExchangeRateError? = null,
)
