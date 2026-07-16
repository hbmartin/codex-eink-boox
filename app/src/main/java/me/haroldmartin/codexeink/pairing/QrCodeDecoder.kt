package me.haroldmartin.codexeink.pairing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

object QrCodeDecoder {
    fun decode(contentResolver: ContentResolver, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open QR image" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid QR image" }

        var sampleSize = 1
        val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (largestDimension / sampleSize > MAX_DECODE_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to reopen QR image" }
            requireNotNull(BitmapFactory.decodeStream(input, null, options)) { "Invalid QR image" }
        }
        try {
            decode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    fun decode(bitmap: Bitmap): String? = runCatching {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()

    private const val MAX_DECODE_DIMENSION = 2_048
}
