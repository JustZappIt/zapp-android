// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import co.electriccoin.zcash.ui.screen.chat.common.runChatCallResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageProcessor {
    private const val THUMBNAIL_MAX_SIZE = 400
    private const val IMAGE_QUALITY = 85
    private const val MAX_IMAGE_SIZE = 1920

    // A fully-decoded ARGB_8888 bitmap costs width*height*4 bytes, so cap the source pixel
    // count before decoding. 100 MP ≈ 400 MB if decoded raw; bounds-only pre-pass + this cap
    // reject decompression bombs (tiny file, enormous declared dimensions) before any
    // allocation, while still admitting every real phone-camera photo.
    private const val MAX_DECODE_PIXELS = 100_000_000L

    // Peer-supplied thumbnails are small JPEGs (~THUMBNAIL_MAX_SIZE px). Cap the base64 length
    // before Base64.decode so a hostile peer can't force a multi-MB allocation up front; the
    // pixel-bounds check below then guards against bombs that are small on the wire.
    private const val MAX_THUMBNAIL_BASE64_CHARS = 512 * 1024

    fun compressImage(context: Context, uri: Uri, maxSize: Int = MAX_IMAGE_SIZE): File? =
        runChatCallResult("ImageProcessor: compressImage failed") {
            val bitmap = decodeSampledFromUri(context, uri, maxSize) ?: return null

            val scaledBitmap = scaleBitmap(bitmap, maxSize)
            val outputFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outputFile).use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, out)
            }
            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            bitmap.recycle()
            outputFile
        }.getOrNull()

    fun generateThumbnail(context: Context, uri: Uri): String? =
        runChatCallResult("ImageProcessor: generateThumbnail failed") {
            val bitmap = decodeSampledFromUri(context, uri, THUMBNAIL_MAX_SIZE) ?: return null

            val thumbnail = scaleBitmap(bitmap, THUMBNAIL_MAX_SIZE)
            val base64 = bitmapToBase64(thumbnail)
            if (thumbnail !== bitmap) thumbnail.recycle()
            bitmap.recycle()
            base64
        }.getOrNull()

    /**
     * Safely decode a remote peer's base64 thumbnail. Caps the encoded length before
     * allocating, runs a bounds-only pre-pass, rejects oversized sources, and downsamples so a
     * hostile peer cannot OOM-crash the chat with a decompression-bomb thumbnail (remote DoS).
     * Returns null on any failure or oversize.
     */
    fun decodePeerThumbnail(base64: String?): Bitmap? {
        if (base64 == null || base64.length > MAX_THUMBNAIL_BASE64_CHARS) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            decodeSampledFromBytes(bytes, THUMBNAIL_MAX_SIZE)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeSampledFromUri(context: Context, uri: Uri, maxSize: Int): Bitmap? =
        decodeSampled(maxSize) { options ->
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }

    private fun decodeSampledFromBytes(bytes: ByteArray, maxSize: Int): Bitmap? =
        decodeSampled(maxSize) { options ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }

    /**
     * Shared two-pass decode: [decode] is first called with bounds-only options to read the
     * source dimensions without allocating, the size is rejected if it exceeds the cap, then
     * [decode] runs again with a computed [BitmapFactory.Options.inSampleSize] to produce a
     * downsampled bitmap. A missing/unreadable source leaves the bounds at zero and is rejected.
     */
    private fun decodeSampled(maxSize: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (!boundsWithinLimit(bounds)) return null

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, maxSize) }
        return decode(options)
    }

    private fun boundsWithinLimit(bounds: BitmapFactory.Options): Boolean {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        return bounds.outWidth.toLong() * bounds.outHeight.toLong() <= MAX_DECODE_PIXELS
    }

    private fun sampleSizeFor(bounds: BitmapFactory.Options, maxSize: Int): Int {
        var sample = 1
        var largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / 2 >= maxSize) {
            largest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap

        val ratio = width.toFloat() / height.toFloat()
        val (newWidth, newHeight) =
            if (width > height) {
                maxSize to (maxSize / ratio).toInt()
            } else {
                (maxSize * ratio).toInt() to maxSize
            }
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
    }
}
