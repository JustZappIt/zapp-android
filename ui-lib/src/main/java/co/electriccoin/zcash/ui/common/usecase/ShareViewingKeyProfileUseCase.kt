package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.spackle.getInternalCacheDirSuspend
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.util.FileShareUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

private const val CACHE_SUBDIR = "viewing_key_export"
private const val EXPORT_FILE_NAME = "zapp-viewing-key.json"
private const val JSON_MIME_TYPE = "application/json"
private const val PROFILE_SCHEMA = "zapp.zcash-viewing-key.v1"
private val profileJson = Json { prettyPrint = true }

class ShareViewingKeyProfileUseCase(
    private val context: Context,
    private val versionInfoProvider: GetVersionInfoProvider,
) {
    suspend operator fun invoke(
        data: ViewingKeyExportResult.Available,
        sharePickerText: String,
    ): Boolean =
        runCatching {
            val file =
                withContext(Dispatchers.IO) {
                    val cacheDir = context.getInternalCacheDirSuspend(CACHE_SUBDIR)
                    cacheDir.listFiles()?.forEach { it.delete() }
                    File(cacheDir, EXPORT_FILE_NAME).also { exportFile ->
                        exportFile.writeText(
                            createViewingKeyProfileJson(
                                data = data,
                                accountName = data.accountLabel.getString(context),
                            )
                        )
                    }
                }
            val shareIntent =
                FileShareUtil.newShareContentIntent(
                    context = context,
                    file = file,
                    fileType = JSON_MIME_TYPE,
                    sharePickerText = sharePickerText,
                    versionInfo = versionInfoProvider(),
                )
            context.startActivity(shareIntent)
            true
        }.getOrElse { false }
}

internal fun createViewingKeyProfileJson(
    data: ViewingKeyExportResult.Available,
    accountName: String,
): String =
    profileJson.encodeToString(
        buildJsonObject {
            put("schema", PROFILE_SCHEMA)
            put("network", data.network.networkName)
            put("account_name", accountName)
            put("account_index", data.accountIndex)
            put("key_type", data.keyType.name.lowercase())
            put("viewing_key", data.encodedKey)
        }
    )
