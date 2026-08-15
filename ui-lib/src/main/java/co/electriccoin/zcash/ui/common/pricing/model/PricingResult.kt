package co.electriccoin.zcash.ui.common.pricing.model

sealed interface PricingFailure {
    data object SeriesUnavailable : PricingFailure

    data class InvalidResponse(
        val reason: String,
    ) : PricingFailure

    data class Http(
        val status: Int,
    ) : PricingFailure

    data class Network(
        val cause: Throwable,
    ) : PricingFailure
}

sealed interface PricingResult<out T> {
    data class Success<T>(
        val value: T,
    ) : PricingResult<T>

    data class Failure(
        val failure: PricingFailure,
    ) : PricingResult<Nothing>
}
