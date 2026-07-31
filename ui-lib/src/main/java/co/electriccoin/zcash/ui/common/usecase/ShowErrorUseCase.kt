package co.electriccoin.zcash.ui.common.usecase

import android.app.Application
import android.widget.Toast
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.stringRes

class ShowErrorUseCase(
    private val application: Application
) {
    operator fun invoke(message: StringResource = stringRes(R.string.error_general_title)) {
        Toast
            .makeText(
                application,
                message.getString(application),
                Toast.LENGTH_LONG
            ).show()
    }
}
