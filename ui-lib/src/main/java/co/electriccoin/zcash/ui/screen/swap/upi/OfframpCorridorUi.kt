package co.electriccoin.zcash.ui.screen.swap.upi

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import xyz.justzappit.offramp.p2p.CurrencyCode

internal data class OfframpCorridorUi(
    @param:DrawableRes val flag: Int,
    @param:StringRes val currencyName: Int,
    @param:StringRes val countryName: Int,
)

internal fun CurrencyCode.toOfframpCorridorUi(): OfframpCorridorUi =
    when (this) {
        CurrencyCode.Inr -> {
            OfframpCorridorUi(
                R.drawable.ic_corridor_in,
                R.string.currency_inr_name,
                R.string.country_india,
            )
        }

        CurrencyCode.Ngn -> {
            OfframpCorridorUi(
                R.drawable.ic_corridor_ng,
                R.string.currency_ngn_name,
                R.string.country_nigeria,
            )
        }

        CurrencyCode.Brl -> {
            OfframpCorridorUi(
                R.drawable.ic_corridor_br,
                R.string.currency_brl_name,
                R.string.country_brazil,
            )
        }

        CurrencyCode.Cop -> {
            OfframpCorridorUi(
                R.drawable.ic_corridor_co,
                R.string.currency_cop_name,
                R.string.country_colombia,
            )
        }

        CurrencyCode.Idr -> {
            OfframpCorridorUi(
                R.drawable.ic_corridor_id,
                R.string.currency_idr_name,
                R.string.country_indonesia,
            )
        }

        CurrencyCode.Ars -> {
            OfframpCorridorUi(
                R.drawable.ic_corridor_ar,
                R.string.currency_ars_name,
                R.string.country_argentina,
            )
        }

        CurrencyCode.Ven -> {
            OfframpCorridorUi(
                R.drawable.ic_corridor_ve,
                R.string.currency_ven_name,
                R.string.country_venezuela,
            )
        }
    }
