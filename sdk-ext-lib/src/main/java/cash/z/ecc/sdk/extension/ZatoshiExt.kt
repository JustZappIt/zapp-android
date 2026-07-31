package cash.z.ecc.sdk.extension

import cash.z.ecc.android.sdk.model.Zatoshi
import kotlin.math.floor

val Zatoshi.Companion.ZERO: Zatoshi
    get() = Zatoshi(0)

@Suppress("MagicNumber")
fun Zatoshi.floor(): Zatoshi = Zatoshi(floorRoundBy(value.toDouble(), 5000.0).toLong())

val Zatoshi.Companion.typicalFee: Zatoshi
    get() = Zatoshi(TYPICAL_FEE)

private const val TYPICAL_FEE = 100000L

private fun floorRoundBy(number: Double, multiple: Double): Double {
    require(multiple != 0.0) { "Multiple cannot be zero" }
    return floor(number / multiple) * multiple
}
