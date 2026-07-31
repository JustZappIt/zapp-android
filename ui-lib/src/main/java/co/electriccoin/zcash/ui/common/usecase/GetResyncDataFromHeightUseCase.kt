package co.electriccoin.zcash.ui.common.usecase

import android.app.Application
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.common.model.VersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class GetResyncDataFromHeightUseCase(
    private val application: Application
) {
    suspend operator fun invoke(blockHeight: BlockHeight): YearMonth =
        withContext(Dispatchers.IO) {
            val instant =
                SdkSynchronizer.estimateBirthdayDate(
                    context = application,
                    height = blockHeight,
                    network = VersionInfo.NETWORK
                )

            val yearMonth =
                ZonedDateTime
                    .ofInstant(
                        java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()),
                        ZoneId.systemDefault()
                    ).let { YearMonth.of(it.year, it.month) }
            yearMonth
        }
}
