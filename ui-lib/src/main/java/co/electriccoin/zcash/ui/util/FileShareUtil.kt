package co.electriccoin.zcash.ui.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import co.electriccoin.zcash.ui.common.model.VersionInfo
import java.io.File

object FileShareUtil {
    const val SHARE_OUTSIDE_THE_APP_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK

    const val SHARE_CONTENT_PERMISSION_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION

    const val ZASHI_INTERNAL_DATA_MIME_TYPE = "application/octet-stream"
    const val ZASHI_QR_CODE_MIME_TYPE = "image/png"

    /**
     * Returns a new share internal app data intent with necessary permission granted exclusively to the data file.
     *
     * @param dataFilePath The private data file path we want to share
     *
     * @return Intent for launching an app for sharing
     */
    @Suppress("LongParameterList")
    internal fun newShareContentIntent(
        context: Context,
        dataFilePath: String,
        fileType: String,
        shareText: String? = null,
        sharePickerText: String,
        versionInfo: VersionInfo,
    ): Intent =
        newShareContentIntent(
            context = context,
            file = File(dataFilePath),
            shareText = shareText,
            sharePickerText = sharePickerText,
            versionInfo = versionInfo,
            fileType = fileType,
        )

    @Suppress("UNUSED_PARAMETER")
    internal fun newShareContentIntent(
        context: Context,
        file: File,
        shareText: String? = null,
        sharePickerText: String,
        versionInfo: VersionInfo,
        fileType: String = ZASHI_INTERNAL_DATA_MIME_TYPE,
    ): Intent {
        val fileUri =
            FileProvider.getUriForFile(
                context,
                fileProviderAuthority(context),
                file
            )

        val dataIntent: Intent =
            Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, fileUri)
                if (shareText != null) {
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                type = fileType
            }

        val shareDataIntent =
            Intent
                .createChooser(
                    dataIntent,
                    sharePickerText
                ).apply {
                    addFlags(
                        SHARE_CONTENT_PERMISSION_FLAGS or
                            SHARE_OUTSIDE_THE_APP_FLAGS
                    )
                }

        return shareDataIntent
    }

    /**
     * The authority registered in every variant's AndroidManifest as
     * `android:authorities="${applicationId}.provider"`. Reading it from the
     * runtime package name keeps the runtime aligned with the manifest no
     * matter which build variant or fork's applicationId is in play.
     */
    fun fileProviderAuthority(context: Context): String = "${context.packageName}.provider"
}
