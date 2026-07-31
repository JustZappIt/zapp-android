package co.electriccoin.zcash.ui.screen.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import co.electriccoin.zcash.spackle.Twig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zxingcpp.BarcodeReader

sealed class ImageToQrCodeResult {
    data class SingleCode(
        val text: String
    ) : ImageToQrCodeResult()

    data object MultipleCodes : ImageToQrCodeResult()

    data object NoCode : ImageToQrCodeResult()
}

class ImageUriToQrCodeConverter {
    // Same reader and options as the live camera analyzer (foss QrCodeAnalyzerImpl), so a saved
    // image decodes anything the camera can — including inverted (dark-mode screenshot) and
    // rotated codes, which the previous Java-ZXing decode could not handle at all.
    private val reader =
        BarcodeReader(
            BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.QR_CODE),
                tryHarder = true,
                tryRotate = true,
                tryInvert = true,
            )
        )

    suspend operator fun invoke(
        context: Context,
        uri: Uri
    ): ImageToQrCodeResult =
        withContext(Dispatchers.IO) {
            runCatching {
                decode(context, uri)
            }.onFailure {
                Twig.error(it) { "Failed to convert Uri to QR code" }
            }.getOrDefault(ImageToQrCodeResult.NoCode)
        }

    private fun decode(
        context: Context,
        uri: Uri
    ): ImageToQrCodeResult {
        val bitmap = uri.toBitmap(context) ?: return ImageToQrCodeResult.NoCode

        val texts =
            try {
                reader
                    .read(bitmap)
                    .mapNotNull { it.text }
                    .distinct()
            } finally {
                bitmap.recycle()
            }

        return when (texts.size) {
            0 -> ImageToQrCodeResult.NoCode
            1 -> ImageToQrCodeResult.SingleCode(texts.first())
            else -> ImageToQrCodeResult.MultipleCodes
        }
    }

    private fun Uri.toBitmap(context: Context): Bitmap? {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        // A bounds-only decode always returns null; it only fills in outWidth/outHeight.
        val boundsStream = context.contentResolver.openInputStream(this) ?: return null
        boundsStream.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            }
        return context.contentResolver.openInputStream(this)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    // Full-resolution camera photos (12MP+) allocate 50-100MB+ as ARGB_8888, which can OOM
    // low-RAM devices before the decode even starts. Halve until the longest edge fits.
    private fun calculateInSampleSize(
        width: Int,
        height: Int
    ): Int {
        var inSampleSize = 1
        while (maxOf(width, height) / inSampleSize > MAX_DECODE_DIMENSION_PX) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private companion object {
        const val MAX_DECODE_DIMENSION_PX = 3000
    }
}
